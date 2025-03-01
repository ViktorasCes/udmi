package daq.pubber.impl.provider;

import static com.google.udmi.util.GeneralUtils.friendlyStackTrace;
import static com.google.udmi.util.GeneralUtils.runtimeExec;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.udmi.util.SiteModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import udmi.lib.base.ManagerBase;
import udmi.lib.client.manager.LocalnetManager;
import udmi.lib.intf.ManagerHost;
import udmi.schema.FamilyLocalnetState;

/**
 * Wrapper for family of IP-based protocols.
 */
public class PubberIpProvider extends ManagerBase implements PubberFamilyProvider {

  public static final int DEFAULT_METRIC = 0;
  private static final List<Pattern> familyPatterns = ImmutableList.of(
      Pattern.compile(" +(inet) ([.\\d]+)/.+"),
      Pattern.compile(" +(inet6) ([:\\da-f]+)/.+"),
      Pattern.compile(" +link/(ether) ([:\\da-f]+) .+")
  );
  private static final Map<String, String> IFACE_MAP = ImmutableMap.of(
      "ether", "ether",
      "inet", "ipv4",
      "inet6", "ipv6"
  );

  private final LocalnetManager localnetHost;
  private final String family;

  /**
   * Create a basic provider instance.
   */
  public PubberIpProvider(ManagerHost host, String family, String deviceId) {
    super(host, deviceId);
    localnetHost = (LocalnetManager) host;
    this.family = family;
    populateInterfaceAddresses();
  }

  /**
   * Get default interface.
   */
  @VisibleForTesting
  public static String getDefaultInterfaceStatic(List<String> routeLines) {
    AtomicReference<String> currentInterface = new AtomicReference<>();
    AtomicInteger currentMaxMetric = new AtomicInteger(Integer.MAX_VALUE);
    routeLines.forEach(line -> {
      try {
        String[] parts = line.split(" ", 13);
        int baseIndex = parts[0].equals("none") ? 1 : 0;
        if (parts[baseIndex].equals("default")) {
          int metric = parts.length < (baseIndex + 11) ? DEFAULT_METRIC
              : Integer.parseInt(parts[baseIndex + 10]);
          if (metric < currentMaxMetric.get()) {
            currentMaxMetric.set(metric);
            currentInterface.set(parts[baseIndex + 4]);
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(format("While processing ip route line: %s", line), e);
      }
    });
    return currentInterface.get();
  }

  /**
   * Try to use first match per family.
   *
   * <pre>
   * ip addr show eth0
   * 149: eth0@if150: *BROADCAST,MULTICAST,UP,LOWER_UP,M-DOWN> mtu 1500 qdisc noqueue state UP
   *     link/ether 02:42:ac:11:00:10 brd ff:ff:ff:ff:ff:ff
   *     inet 10.0.0.10/24 brd 10.0.0.255 scope global eth0
   *        valid_lft forever preferred_lft forever
   *     inet6 fd00:1234:abc:1::10/120 scope global flags 02
   *        valid_lft forever preferred_lft forever
   *     inet6 fe80::42:acff:fe11:10/64 scope link
   *        valid_lft forever preferred_lft forever
   *
   * ip -6 route
   * fd00:1234:abc:1::/120 dev eth0  metric 256
   * fe80::/64 dev eth0  metric 256
   * default via fd00:1234:abc:1::1 dev eth0  metric 1024
   * multicast ff00::/8 dev eth0  metric 256
   * </pre>
   */
  @VisibleForTesting
  public static Map<String, String> getInterfaceAddressesStatic(List<String> strings) {
    Map<String, String> interfaceMap = new HashMap<>();
    strings.forEach(line -> {
      for (Pattern pattern : familyPatterns) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
          String family = IFACE_MAP.get(matcher.group(1));
          interfaceMap.putIfAbsent(family, matcher.group(2));
        }
      }
    });
    return interfaceMap;
  }

  private String getDefaultInterface() {
    final List<String> routeLines;
    try {
      routeLines = runtimeExec("ip", "route");
    } catch (Exception e) {
      error(format("Could not execute ip route command: %s", friendlyStackTrace(e)));
      return null;
    }
    try {
      return getDefaultInterfaceStatic(routeLines);
    } catch (Exception e) {
      error(format("Could not infer default interface: %s", friendlyStackTrace(e)));
      return null;
    }
  }

  /**
   * Parse the output of ip route/addr and turn it into a family addr map.
   *
   * <p>Start with default route with the lowest metric, and then parse the interface addresses.
   *
   * <pre>
   * peringknife@peringknife-glaptop4:~/udmi$ ip route
   * default via 192.168.8.1 dev enp0s31f6 proto dhcp src 192.168.8.3 metric 100
   * default via 10.0.0.1 dev wlp0s20f3 proto dhcp src 10.0.0.142 metric 600
   * 10.0.0.0/24 dev wlp0s20f3 proto kernel scope link src 10.0.0.142 metric 600
   * 192.168.8.0/24 dev enp0s31f6 proto kernel scope link src 192.168.8.3 metric 100
   * 192.168.9.0/24 dev docker0 proto kernel scope link src 192.168.9.1 linkdown
   *
   * peringknife@peringknife-glaptop4:~/udmi$ ip addr show dev enp0s31f6
   * 2: enp0s31f6: *BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc .... 1000
   *     link/ether 8c:8c:aa:50:bc:72 brd ff:ff:ff:ff:ff:ff
   *     inet 192.168.8.3/24 brd 192.168.8.255 scope global dynamic noprefixroute enp0s31f6
   *        valid_lft 83596sec preferred_lft 83596sec
   *     inet6 fe80::11a9:496f:6596:b455/64 scope link noprefixroute
   *        valid_lft forever preferred_lft forever
   * </pre>
   */
  private void populateInterfaceAddresses() {
    String defaultInterface = getDefaultInterface();
    info(format("Using addresses from default interface: %s for family: %s",
        defaultInterface, family));
    Map<String, String> interfaceAddresses = ofNullable(getInterfaceAddresses(defaultInterface))
        .orElse(ImmutableMap.of());
    interfaceAddresses.entrySet().forEach(this::addStateMapEntry);
  }

  private void addStateMapEntry(Entry<String, String> entry) {
    String family = entry.getKey();
    if (!Objects.equals(this.family, family)) {
      return;
    }
    FamilyLocalnetState stateEntry = new FamilyLocalnetState();
    stateEntry.addr = entry.getValue();
    info(format("Family %s address is %s", family, stateEntry.addr));
    localnetHost.update(family, stateEntry);
  }

  private Map<String, String> getInterfaceAddresses(String defaultInterface) {
    if (defaultInterface == null) {
      return null;
    }
    final List<String> strings;
    try {
      strings = runtimeExec("ip", "addr", "show", "dev", defaultInterface);
    } catch (Exception e) {
      error(format("Could not execute ip addr command: %s", friendlyStackTrace(e)));
      return null;
    }

    try {
      return getInterfaceAddressesStatic(strings);
    } catch (Exception e) {
      error(format("Could not infer interface addresses: %s", friendlyStackTrace(e)));
      return null;
    }
  }

  @Override
  public void setSiteModel(SiteModel siteModel) {
  }
}

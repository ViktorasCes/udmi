
## writeback_invalid (ALPHA)

1. Wait until target point has value_state default (null)
1. Update config before target point has value_state invalid
    * Add `pointset.points.filter_differential_pressure_sensor.set_value` = `15`
1. Wait until target point has value_state invalid

Test passed.

package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class TemperatureTest {

    @Test
    void celsiusToFahrenheit() {
        var celsius = new Temperature(BigDecimal.valueOf(100), Temperature.TemperatureUnit.CELSIUS);
        var fahrenheit = celsius.toFahrenheit();
        assertThat(fahrenheit.unit()).isEqualTo(Temperature.TemperatureUnit.FAHRENHEIT);
        assertThat(fahrenheit.value()).isEqualByComparingTo(BigDecimal.valueOf(212));
    }

    @Test
    void fahrenheitToCelsius() {
        var fahrenheit = new Temperature(BigDecimal.valueOf(32), Temperature.TemperatureUnit.FAHRENHEIT);
        var celsius = fahrenheit.toCelsius();
        assertThat(celsius.unit()).isEqualTo(Temperature.TemperatureUnit.CELSIUS);
        assertThat(celsius.value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void celsiusToCelsiusReturnsSameInstance() {
        var celsius = new Temperature(BigDecimal.valueOf(20), Temperature.TemperatureUnit.CELSIUS);
        assertThat(celsius.toCelsius()).isSameAs(celsius);
    }

    @Test
    void fahrenheitToFahrenheitReturnsSameInstance() {
        var fahrenheit = new Temperature(BigDecimal.valueOf(68), Temperature.TemperatureUnit.FAHRENHEIT);
        assertThat(fahrenheit.toFahrenheit()).isSameAs(fahrenheit);
    }

    @Test
    void recordEquality() {
        var a = new Temperature(BigDecimal.valueOf(20), Temperature.TemperatureUnit.CELSIUS);
        var b = new Temperature(BigDecimal.valueOf(20), Temperature.TemperatureUnit.CELSIUS);
        assertThat(a).isEqualTo(b);
    }
}

import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.io.i2c.I2C
import com.pi4j.io.i2c.I2CProvider

sealed class I2CConnection {
    abstract fun write(bytes: ByteArray)

    class Linux(val bus: Int, val device: Int) : I2CConnection() {
        val pi4j: Context = Pi4J.newAutoContext()
        val i2cProvider: I2CProvider = pi4j.provider("linuxfs-i2c")
        val i2cConfig = I2C.newConfigBuilder(pi4j)
            .id("ssd1306")
            .bus(bus)
            .device(device)
            .build()
        val displayDevice = i2cProvider.create(i2cConfig)

        override fun write(bytes: ByteArray) {
            displayDevice.write(bytes)
        }
    }

    class Trace : I2CConnection() {
        override fun write(bytes: ByteArray) {
            println(bytes.joinToString(" ") {
                it.toInt().and(0xff).toString(16).padStart(2, '0')
            })
        }
    }
}
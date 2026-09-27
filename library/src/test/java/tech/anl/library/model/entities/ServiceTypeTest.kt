package tech.anl.library.model.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceTypeTest {
    private val converter = ServiceTypeConverter()

    @Test
    fun `ssh round-trips through the database converter`() {
        assertEquals("ssh", converter.fromServiceType(ServiceType.Ssh))
        assertEquals(ServiceType.Ssh, converter.fromString("ssh"))
    }

    @Test
    fun `legacy vnc and xsdl sessions are read back as SSH`() {
        assertEquals(ServiceType.Ssh, converter.fromString("vnc"))
        assertEquals(ServiceType.Ssh, converter.fromString("xsdl"))
    }

    @Test
    fun `anything else is unselected`() {
        assertEquals(ServiceType.Unselected, converter.fromString("unselected"))
        assertEquals(ServiceType.Unselected, converter.fromString(""))
    }
}

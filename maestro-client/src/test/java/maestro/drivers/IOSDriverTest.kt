package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import hierarchy.AXElement
import hierarchy.AXFrame
import hierarchy.ViewHierarchy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ios.IOSDeviceErrors
import maestro.CLIPS_CHILDREN_ATTRIBUTE
import maestro.DeviceUnreachableException
import maestro.MaestroException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.api.DeviceInfo
import java.net.SocketTimeoutException

class IOSDriverTest {

    @Test
    fun `IOSDeviceErrors Unreachable from the device is translated to DeviceUnreachableException`() {
        val cause = SocketTimeoutException("Read timed out")
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", cause)

        val driver = IOSDriver(iosDevice)

        val thrown = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(thrown.operation).isEqualTo("deviceInfo")
        assertThat(thrown.cause).isInstanceOf(IOSDeviceErrors.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `IOSDriver does not cache - subsequent calls invoke the device again`() {
        // Fail-fast for the dead-runner case lives at the transport layer (XCTestDriverClient).
        // IOSDriver is now a thin translator: it converts each IOSDeviceErrors.Unreachable into
        // a DeviceUnreachableException without short-circuiting on its own. When the underlying
        // device keeps throwing (mimicking a still-tripped transport latch), the driver translates
        // each call independently.
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", SocketTimeoutException())

        val driver = IOSDriver(iosDevice)

        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `non-transport exceptions still translate to their MaestroException counterparts`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.AppCrash("crashed")

        val driver = IOSDriver(iosDevice)

        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `successful calls pass through unchanged`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } returns DeviceInfo(
            widthPixels = 1170,
            heightPixels = 2532,
            widthPoints = 390,
            heightPoints = 844,
        )

        val driver = IOSDriver(iosDevice)

        driver.deviceInfo()
        driver.deviceInfo()
        driver.deviceInfo()

        verify(exactly = 3) { iosDevice.deviceInfo() }
    }

    @Test
    fun `scroll views, tables and collection views are marked as clipping their content when enabled`() {
        val root = IOSDriver(iosDeviceWithScrollingElements(), clipToScrollContainers = true)
            .contentDescriptor(excludeKeyboardElements = false)

        val clipping = root.aggregate()
            .filter { it.attributes[CLIPS_CHILDREN_ATTRIBUTE] == "true" }
            .map { it.attributes["resource-id"] }
        assertThat(clipping).containsExactly("scroll-view", "table", "collection-view")
    }

    @Test
    fun `nothing is marked as clipping by default`() {
        val root = IOSDriver(iosDeviceWithScrollingElements())
            .contentDescriptor(excludeKeyboardElements = false)

        assertThat(root.aggregate().filter { it.attributes.containsKey(CLIPS_CHILDREN_ATTRIBUTE) }).isEmpty()
    }

    private fun iosDeviceWithScrollingElements(): IOSDevice {
        fun element(elementType: Int, identifier: String, children: List<AXElement> = emptyList()) = AXElement(
            label = "",
            elementType = elementType,
            identifier = identifier,
            horizontalSizeClass = 0,
            windowContextID = 0,
            verticalSizeClass = 0,
            selected = false,
            displayID = 0,
            hasFocus = false,
            placeholderValue = null,
            value = null,
            frame = AXFrame(x = 0f, y = 0f, width = 402f, height = 874f),
            enabled = true,
            title = null,
            children = ArrayList(children),
        )
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.viewHierarchy(any()) } returns ViewHierarchy(
            axElement = element(
                elementType = 2,
                identifier = "application",
                children = listOf(
                    element(elementType = 46, identifier = "scroll-view"),
                    element(elementType = 26, identifier = "table"),
                    element(elementType = 32, identifier = "collection-view"),
                    element(elementType = 1, identifier = "other"),
                    element(elementType = 75, identifier = "cell"),
                ),
            ),
            depth = 2,
        )
        return iosDevice
    }
}

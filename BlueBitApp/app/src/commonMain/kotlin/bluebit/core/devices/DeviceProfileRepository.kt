package bluebit.core.devices

interface DeviceProfileRepository {
    fun findByIdentifier(id: Int): DeviceProfile?
    fun findByName(name: String): DeviceProfile?
}

class InMemoryDeviceProfileRepository(
    private val profiles: List<DeviceProfile> = defaultProfiles
) : DeviceProfileRepository {
    override fun findByIdentifier(id: Int): DeviceProfile? = profiles.find { it.identifier == id }
    override fun findByName(name: String): DeviceProfile? =
        profiles.find { it.name.equals(name, ignoreCase = true) }
}

val defaultProfiles: List<DeviceProfile> = emptyList()

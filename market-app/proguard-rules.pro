# Kotlin serialization keeps serializers through generated references. Ktor/Room/Coil do not
# require broad keep rules here. Keep the release surface minimal and let R8 shrink aggressively.

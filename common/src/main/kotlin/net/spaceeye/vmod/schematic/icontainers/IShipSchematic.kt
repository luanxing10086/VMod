package net.spaceeye.vmod.schematic.icontainers

import io.netty.buffer.ByteBuf
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import java.util.UUID

interface IShipSchematic {
    val schematicVersion: Int

    fun getInfo(): IShipSchematicInfo

    fun placeAt(level: ServerLevel, uuid: UUID, pos: Vector3d, rotation: Quaterniondc): Boolean
    fun makeFrom(level: ServerLevel, uuid: UUID, originShip: ServerShip, postSaveFn: () -> Unit = {}): Boolean

    fun saveToFile(): IFile
    fun loadFromByteBuffer(buf: ByteBuf): Boolean
}
package net.spaceeye.vmod.transformProviders

import net.minecraft.client.Minecraft
import net.spaceeye.vmod.toolgun.ToolgunItem
import net.spaceeye.vmod.toolgun.modes.PositionModes
import net.spaceeye.vmod.utils.*
import org.joml.Quaterniond
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.ClientShipTransformProvider
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.core.impl.game.ships.ShipTransformImpl

class PlacementAssistTransformProvider(
    var firstResult: RaycastFunctions.RaycastResult,
    var mode: PositionModes,
    var ship1: ClientShip
): ClientShipTransformProvider {
    val level = Minecraft.getInstance().level!!
    val player = Minecraft.getInstance().cameraEntity!!

    lateinit var rresult2: RaycastFunctions.RaycastResult
    lateinit var spoint1: Vector3d
    lateinit var spoint2: Vector3d

    lateinit var gdir1: Vector3d
    lateinit var gdir2: Vector3d

    override fun provideNextRenderTransform(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform,
        partialTick: Double
    ): ShipTransform? {
        //TODO think of a better way
        if (!ToolgunItem.playerIsUsingToolgun()) {return null}
        val secondResult = RaycastFunctions.raycast(
            level,
            RaycastFunctions.Source(
                Vector3d(Minecraft.getInstance().gameRenderer.mainCamera.lookVector).snormalize(),
                Vector3d(Minecraft.getInstance().player!!.eyePosition)
            ),
            100.0,
            ship1.id,
            {ship, dir -> transformDirectionShipToWorldRender(ship as ClientShip, dir) },
            {ship, dir -> transformDirectionWorldToShipRender(ship as ClientShip, dir) },
            {ship, pos, transform -> posShipToWorldRender(ship as ClientShip, pos, transform) },
            {ship, pos, transform -> posWorldToShipRender(ship as ClientShip, pos, transform) }
        )
        rresult2 = secondResult

        if (firstResult.globalNormalDirection == null || secondResult.worldNormalDirection == null) { return null }
        // not sure why i need to flip normal but it works
        val dir1 = when {
            firstResult.globalNormalDirection!!.y ==  1.0 -> -firstResult.globalNormalDirection!!
            firstResult.globalNormalDirection!!.y == -1.0 -> -firstResult.globalNormalDirection!!
            else -> firstResult.globalNormalDirection!!
        }
        val dir2 = secondResult.worldNormalDirection!!

        gdir1 = dir1
        gdir2 = secondResult.globalNormalDirection!!

        var rotation = Quaterniond()
        if (!secondResult.state.isAir) {
            rotation = Quaterniond()
                .mul(getQuatFromDir(dir2)) // this rotates ship to align with world normal
                .mul(getQuatFromDir(dir1)) // this rotates ship so that it aligns with hit pos normal
                .normalize()
        }

        spoint1 = if (mode == PositionModes.NORMAL) {firstResult.globalHitPos!!} else {firstResult.globalCenteredHitPos!!}
        spoint2 = if (mode == PositionModes.NORMAL) {secondResult.globalHitPos!!} else {secondResult.globalCenteredHitPos!!}
        val rpoint2 = if (mode == PositionModes.NORMAL) {secondResult.worldHitPos!!} else {secondResult.worldCenteredHitPos!!}

        // ship transform modifies both position in world AND rotation, but while we don't care about position in world,
        // rotation is incredibly important

        val point = rpoint2 - (
            posShipToWorldRender(ship1, spoint1, (ship1.renderTransform as ShipTransformImpl).copy(shipToWorldRotation = rotation)) -
            posShipToWorldRender(ship1, Vector3d(ship1.renderTransform.positionInShip), (ship1.renderTransform as ShipTransformImpl).copy(shipToWorldRotation = rotation))
        )

        return ShipTransformImpl(
            point.toJomlVector3d(),
            shipTransform.positionInShip,
            rotation,
            shipTransform.shipToWorldScaling
        )
    }

    override fun provideNextTransform(
        prevShipTransform: ShipTransform,
        shipTransform: ShipTransform,
        latestNetworkTransform: ShipTransform
    ): ShipTransform? {
        return null
    }
}
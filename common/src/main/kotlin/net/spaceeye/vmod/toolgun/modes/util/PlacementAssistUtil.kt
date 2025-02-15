package net.spaceeye.vmod.toolgun.modes.util

import dev.architectury.event.EventResult
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.spaceeye.vmod.VMConfig
import net.spaceeye.vmod.constraintsManaging.VSConstraintsKeeper
import net.spaceeye.vmod.constraintsManaging.addFor
import net.spaceeye.vmod.constraintsManaging.makeManagedConstraint
import net.spaceeye.vmod.constraintsManaging.types.MConstraint
import net.spaceeye.vmod.limits.ServerLimits
import net.spaceeye.vmod.networking.S2CConnection
import net.spaceeye.vmod.networking.Serializable
import net.spaceeye.vmod.toolgun.modes.BaseMode
import net.spaceeye.vmod.transformProviders.CenteredAroundPlacementAssistTransformProvider
import net.spaceeye.vmod.transformProviders.CenteredAroundRotationAssistTransformProvider
import net.spaceeye.vmod.transformProviders.PlacementAssistTransformProvider
import net.spaceeye.vmod.transformProviders.RotationAssistTransformProvider
import net.spaceeye.vmod.utils.*
import org.joml.AxisAngle4d
import org.joml.Quaterniond
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.impl.game.ships.ShipTransformImpl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

interface PlacementAssistCRIHandler {
    var paCaughtShip: ClientShip?
    var paCaughtShips: LongArray?
    var paStage: ThreeClicksActivationSteps
    var posMode: PositionModes

    var paAngle: Ref<Double>

    fun clientHandleMouseClickPA() {
        when (paStage) {
            ThreeClicksActivationSteps.FIRST_RAYCAST  -> clientPlacementAssistFirst()
            ThreeClicksActivationSteps.SECOND_RAYCAST -> clientPlacementAssitSecond()
            ThreeClicksActivationSteps.FINALIZATION   -> clientPlacementAssistThird()
        }
    }

    fun clientHandleMouseEventPA(amount: Double): EventResult {
        if (!(paStage == ThreeClicksActivationSteps.SECOND_RAYCAST || paStage == ThreeClicksActivationSteps.FINALIZATION)) { return EventResult.pass() }

        paAngle.it = paAngle.it + amount * 0.2

        return EventResult.interruptFalse()
    }

    private fun clientPlacementAssistFirst() {
        if (paCaughtShip != null) {
            paClientResetState()
            return
        }

        val raycastResult = RaycastFunctions.raycast(
            Minecraft.getInstance().level!!,
            RaycastFunctions.Source(
                Vector3d(Minecraft.getInstance().gameRenderer.mainCamera.lookVector).snormalize(),
                Vector3d(Minecraft.getInstance().player!!.eyePosition)
            ),
            VMConfig.CLIENT.TOOLGUN.MAX_RAYCAST_DISTANCE
        )

        val level = Minecraft.getInstance().level!!

        if (raycastResult.state.isAir) {paClientResetState(); return}
        val mode = if (posMode != PositionModes.CENTERED_IN_BLOCK) {posMode} else {PositionModes.CENTERED_ON_SIDE}

        paCaughtShip = (level.getShipManagingPos(raycastResult.blockPosition) ?: run {paClientResetState(); return}) as ClientShip
        paCaughtShip!!.transformProvider = PlacementAssistTransformProvider(raycastResult, mode, paCaughtShip!!)

        paStage = ThreeClicksActivationSteps.SECOND_RAYCAST
        return
    }

    private fun clientPlacementAssitSecond() {
        paStage = ThreeClicksActivationSteps.FINALIZATION
        if (paCaughtShip == null) { paClientResetState(); return }

        val placementTransform = paCaughtShip!!.transformProvider
        if (placementTransform !is PlacementAssistTransformProvider) {paClientResetState(); return}

        paAngle.it = 0.0
        paCaughtShip!!.transformProvider = RotationAssistTransformProvider(placementTransform, paAngle)

        val shipObjectWorld = Minecraft.getInstance().shipObjectWorld
        paCaughtShips!!.forEach {
            val ship = shipObjectWorld.allShips.getById(it)
            ship?.transformProvider = CenteredAroundRotationAssistTransformProvider(ship!!.transformProvider as CenteredAroundPlacementAssistTransformProvider)
        }
    }

    private fun clientPlacementAssistThird() {
        paClientResetState()
    }

    fun paClientResetState() {
        paStage = ThreeClicksActivationSteps.FIRST_RAYCAST
        if (paCaughtShip != null) {
            paCaughtShip!!.transformProvider = null
            paCaughtShip = null
        }
        paCaughtShips?.forEach {
            Minecraft.getInstance().shipObjectWorld.allShips.getById(it)?.transformProvider = null
        }
        paCaughtShips = null
    }
}

interface PANetworkingUnit: BaseMode, PlacementAssistServer, PlacementAssistCRIHandler

open class PlacementAssistNetworking(networkName: String) {
    private var obj: PANetworkingUnit? = null
    fun init(mode: PANetworkingUnit) {if (obj == null) { obj = mode} }

    val s2cHandleFailure = "handle_failure" idWithConns {
        object : S2CConnection<S2CHandleFailurePacket>(it, networkName) {
            override fun clientHandler(buf: FriendlyByteBuf, context: NetworkManager.PacketContext) {
                val obj = obj ?: return
                obj.resetState()
            }
        }
    }

    // Client has no information about constraints, so server should send it to the client
    val s2cSendTraversalInfo = "send_traversal_info" idWithConns {
        object : S2CConnection<PlacementAssistNetworking.S2CSendTraversalInfo>(it, networkName) {
            override fun clientHandler(buf: FriendlyByteBuf, context: NetworkManager.PacketContext) {
                val pkt = S2CSendTraversalInfo(buf)
                if (obj!!.paCaughtShip == null) {return}

                val primaryTransform = obj!!.paCaughtShip!!.transformProvider
                if (primaryTransform !is PlacementAssistTransformProvider) { return }

                obj!!.paCaughtShips = pkt.data.filter { it != obj!!.paCaughtShip!!.id }.toLongArray()
                val clientShipObjectWorld = Minecraft.getInstance().shipObjectWorld

                obj!!.paCaughtShips!!.forEach {
                    val ship = clientShipObjectWorld.allShips.getById(it)
                    ship?.transformProvider = CenteredAroundPlacementAssistTransformProvider(primaryTransform, ship!!)
                }
            }
        }
    }

    class S2CHandleFailurePacket(): Serializable {
        override fun serialize(): FriendlyByteBuf { return getBuffer() }
        override fun deserialize(buf: FriendlyByteBuf) {}
    }

    class S2CSendTraversalInfo(): Serializable {
        var data: LongArray = longArrayOf()

        constructor(buf: FriendlyByteBuf): this() { deserialize(buf) }
        constructor(data: MutableSet<ShipId>): this() { this.data = data.toLongArray() }
        override fun serialize(): FriendlyByteBuf {
            val buf = getBuffer()

            buf.writeLongArray(data)

            return buf
        }

        override fun deserialize(buf: FriendlyByteBuf) {
            data = buf.readLongArray()
        }
    }

    infix fun <TT: Serializable> String.idWithConns(constructor: (String) -> S2CConnection<TT>): S2CConnection<TT> {
        val instance = constructor(this)
        try { // Why? so that if it's registered on dedicated client/server it won't die
            NetworkManager.registerReceiver(instance.side, instance.id, instance.getHandler())
        } catch(e: NoSuchMethodError) {}
        return instance
    }
}

interface PlacementAssistSerialize {
    var paAngle: Ref<Double>
    var paDistanceFromBlock: Double
    var paStage: ThreeClicksActivationSteps

    fun paSerialize(buf: FriendlyByteBuf): FriendlyByteBuf {
        buf.writeDouble(paDistanceFromBlock)
        buf.writeDouble(paAngle.it)
        buf.writeEnum(paStage)
        return buf
    }

    fun paDeserialize(buf: FriendlyByteBuf) {
        paDistanceFromBlock = buf.readDouble()
        paAngle.it = buf.readDouble()
        paStage = buf.readEnum(paStage.javaClass)
    }

    fun paServerSideVerifyLimits() {
        val limits = ServerLimits.instance
        paDistanceFromBlock = limits.distanceFromBlock.get(paDistanceFromBlock)
    }
}

interface PlacementAssistServer {
    var paStage: ThreeClicksActivationSteps
    var posMode: PositionModes

    var paAngle: Ref<Double>
    
    var paFirstResult: RaycastFunctions.RaycastResult?
    var paSecondResult: RaycastFunctions.RaycastResult?

    val paMConstraintBuilder: (spoint1: Vector3d, spoint2: Vector3d, rpoint1: Vector3d, rpoint2: Vector3d, ship1: ServerShip, ship2: ServerShip?, shipId1: ShipId, shipId2: ShipId, rresults: Pair<RaycastFunctions.RaycastResult, RaycastFunctions.RaycastResult>) -> MConstraint
    val paNetworkingObject: PlacementAssistNetworking

    val paDistanceFromBlock: Double

    fun activateFunctionPA(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (level !is ServerLevel) {throw RuntimeException("Function intended for server use only was activated on client. How.")}
        when (paStage) {
            ThreeClicksActivationSteps.SECOND_RAYCAST -> paFunctionFirst (level, player, raycastResult)
            ThreeClicksActivationSteps.FINALIZATION   -> paFunctionSecond(level, player, raycastResult)
            ThreeClicksActivationSteps.FIRST_RAYCAST  -> paFunctionThird (level, player, raycastResult)
        }
    }

    private fun handleFailure(player: Player) {
        paNetworkingObject.s2cHandleFailure.sendToClient(player as ServerPlayer, PlacementAssistNetworking.S2CHandleFailurePacket())
        paServerResetState()
    }

    private fun paFunctionFirst(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (raycastResult.state.isAir) {return handleFailure(player)}
        val ship = level.getShipManagingPos(raycastResult.blockPosition) ?: return handleFailure(player)
        paFirstResult = raycastResult
        val traversed = VSConstraintsKeeper.traverseGetConnectedShips(ship.id)
        paNetworkingObject.s2cSendTraversalInfo.sendToClient(player as ServerPlayer, PlacementAssistNetworking.S2CSendTraversalInfo(traversed))
    }

    private fun paFunctionSecond(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (raycastResult.state.isAir) {return handleFailure(player) }
        val ship = level.getShipManagingPos(raycastResult.blockPosition)
        if (ship == level.getShipManagingPos(paFirstResult?.blockPosition ?: return handleFailure(player))) {return handleFailure(player)}
        paSecondResult = raycastResult
    }

    private fun paFunctionThird(level: ServerLevel, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (paFirstResult == null || paSecondResult == null) {return handleFailure(player)}

        val paFirstResult = paFirstResult!!
        val paSecondResult = paSecondResult!!

        val ship1 = level.getShipManagingPos(paFirstResult.blockPosition)
        val ship2 = level.getShipManagingPos(paSecondResult.blockPosition)

        if (ship1 == null) {return handleFailure(player)}
        if (ship1 == ship2) {return handleFailure(player)}

        // not sure why i need to flip normal but it works
        val dir1 =  when {
            paFirstResult.globalNormalDirection!!.y ==  1.0 -> -paFirstResult.globalNormalDirection!!
            paFirstResult.globalNormalDirection!!.y == -1.0 -> -paFirstResult.globalNormalDirection!!
            else -> paFirstResult.globalNormalDirection!!
        }
        val dir2 = if (ship2 != null) { transformDirectionShipToWorld(ship2, paSecondResult.globalNormalDirection!!) } else paSecondResult.globalNormalDirection!!

        val angle = Quaterniond(AxisAngle4d(paAngle.it, dir2.toJomlVector3d()))

        val rotation = Quaterniond()
            .mul(angle)
            .mul(getQuatFromDir(dir2))
            .mul(getQuatFromDir(dir1))
            .normalize()


        val (spoint1, spoint2) = getModePositions(if (posMode == PositionModes.NORMAL) {posMode} else {PositionModes.CENTERED_ON_SIDE}, paFirstResult, paSecondResult)
        var rpoint2 = if (ship2 == null) spoint2 else posShipToWorld(ship2, Vector3d(spoint2))

        // rotation IS IMPORTANT, so make a new transform with new rotation to translate points
        val newTransform = (ship1.transform as ShipTransformImpl).copy(shipToWorldRotation = rotation)

        // we cannot modify position in ship, we can, however, modify position in world
        // this translates ship so that after teleportation its spoint1 will be at rpoint2
        val point = rpoint2 - (
            posShipToWorld(ship1, spoint1, newTransform) - Vector3d(ship1.transform.positionInWorld)
        )

        teleportShipWithConnected(level, ship1, point, rotation)

        rpoint2 = if (ship2 == null) spoint2 else posShipToWorld(ship2, Vector3d(spoint2))
        val rpoint1 = Vector3d(rpoint2) + dir2.normalize() * paDistanceFromBlock

        val shipId1: ShipId = ship1.id
        val shipId2: ShipId = ship2?.id ?: level.shipObjectWorld.dimensionToGroundBodyIdImmutable[level.dimensionId]!!

        val constraint = paMConstraintBuilder(spoint1, spoint2, rpoint1, rpoint2, ship1, ship2, shipId1, shipId2, Pair(paFirstResult, paSecondResult))
        level.makeManagedConstraint(constraint).addFor(player)
        paServerResetState()
    }
    fun paServerResetState() {
        paStage = ThreeClicksActivationSteps.FIRST_RAYCAST
        paFirstResult = null
        paSecondResult = null
    }
}
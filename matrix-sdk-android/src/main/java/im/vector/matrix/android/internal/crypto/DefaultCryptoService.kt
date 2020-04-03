/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.crypto

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import com.squareup.moshi.Types
import com.zhuinden.monarchy.Monarchy
import dagger.Lazy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.NoOpMatrixCallback
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.crypto.MXCryptoConfig
import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.api.listeners.ProgressListener
import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.crypto.MXCryptoError
import im.vector.matrix.android.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.crosssigning.SELF_SIGNING_KEY_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.crosssigning.USER_SIGNING_KEY_SSSS_NAME
import im.vector.matrix.android.api.session.crypto.keyshare.GossipingRequestListener
import im.vector.matrix.android.api.session.events.model.Content
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.Membership
import im.vector.matrix.android.api.session.room.model.RoomHistoryVisibility
import im.vector.matrix.android.api.session.room.model.RoomHistoryVisibilityContent
import im.vector.matrix.android.api.session.room.model.RoomMemberSummary
import im.vector.matrix.android.internal.crypto.actions.MegolmSessionDataImporter
import im.vector.matrix.android.internal.crypto.actions.SetDeviceVerificationAction
import im.vector.matrix.android.internal.crypto.algorithms.IMXEncrypting
import im.vector.matrix.android.internal.crypto.algorithms.megolm.MXMegolmEncryptionFactory
import im.vector.matrix.android.internal.crypto.algorithms.olm.MXOlmEncryptionFactory
import im.vector.matrix.android.internal.crypto.crosssigning.DefaultCrossSigningService
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import im.vector.matrix.android.internal.crypto.keysbackup.DefaultKeysBackupService
import im.vector.matrix.android.internal.crypto.model.CryptoDeviceInfo
import im.vector.matrix.android.internal.crypto.model.ImportRoomKeysResult
import im.vector.matrix.android.internal.crypto.model.MXDeviceInfo
import im.vector.matrix.android.internal.crypto.model.MXEncryptEventContentResult
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap
import im.vector.matrix.android.internal.crypto.model.event.EncryptedEventContent
import im.vector.matrix.android.internal.crypto.model.event.RoomKeyContent
import im.vector.matrix.android.internal.crypto.model.event.SecretSendEventContent
import im.vector.matrix.android.internal.crypto.model.rest.DeviceInfo
import im.vector.matrix.android.internal.crypto.model.rest.DevicesListResponse
import im.vector.matrix.android.internal.crypto.model.rest.KeysUploadResponse
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyRequestBody
import im.vector.matrix.android.internal.crypto.model.toRest
import im.vector.matrix.android.internal.crypto.repository.WarnOnUnknownDeviceRepository
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import im.vector.matrix.android.internal.crypto.tasks.DeleteDeviceTask
import im.vector.matrix.android.internal.crypto.tasks.DeleteDeviceWithUserPasswordTask
import im.vector.matrix.android.internal.crypto.tasks.GetDeviceInfoTask
import im.vector.matrix.android.internal.crypto.tasks.GetDevicesTask
import im.vector.matrix.android.internal.crypto.tasks.SetDeviceNameTask
import im.vector.matrix.android.internal.crypto.tasks.UploadKeysTask
import im.vector.matrix.android.internal.crypto.verification.DefaultVerificationService
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.model.EventEntityFields
import im.vector.matrix.android.internal.database.query.whereType
import im.vector.matrix.android.internal.di.MoshiProvider
import im.vector.matrix.android.internal.extensions.foldToCallback
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.room.membership.LoadRoomMembersTask
import im.vector.matrix.android.internal.session.room.membership.RoomMemberHelper
import im.vector.matrix.android.internal.session.sync.model.SyncResponse
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.TaskThread
import im.vector.matrix.android.internal.task.configureWith
import im.vector.matrix.android.internal.util.JsonCanonicalizer
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import im.vector.matrix.android.internal.util.fetchCopied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.matrix.olm.OlmManager
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.max

/**
 * A `CryptoService` class instance manages the end-to-end crypto for a session.
 *
 *
 * Messages posted by the user are automatically redirected to CryptoService in order to be encrypted
 * before sending.
 * In the other hand, received events goes through CryptoService for decrypting.
 * CryptoService maintains all necessary keys and their sharing with other devices required for the crypto.
 * Specially, it tracks all room membership changes events in order to do keys updates.
 */
@SessionScope
internal class DefaultCryptoService @Inject constructor(
        // Olm Manager
        private val olmManager: OlmManager,
        // The credentials,
        private val credentials: Credentials,
        private val myDeviceInfoHolder: Lazy<MyDeviceInfoHolder>,
        // the crypto store
        private val cryptoStore: IMXCryptoStore,

        // Olm device
        private val olmDevice: MXOlmDevice,
        // Set of parameters used to configure/customize the end-to-end crypto.
        private val mxCryptoConfig: MXCryptoConfig,
        // Device list manager
        private val deviceListManager: DeviceListManager,
        // The key backup service.
        private val keysBackupService: DefaultKeysBackupService,
        //
        private val objectSigner: ObjectSigner,
        //
        private val oneTimeKeysUploader: OneTimeKeysUploader,
        //
        private val roomDecryptorProvider: RoomDecryptorProvider,
        // The verification service.
        private val verificationService: DefaultVerificationService,

        private val crossSigningService: DefaultCrossSigningService,
        //
        private val incomingGossipingRequestManager: IncomingGossipingRequestManager,
        //
        private val outgoingGossipingRequestManager: OutgoingGossipingRequestManager,
        // Actions
        private val setDeviceVerificationAction: SetDeviceVerificationAction,
        private val megolmSessionDataImporter: MegolmSessionDataImporter,
        private val warnOnUnknownDevicesRepository: WarnOnUnknownDeviceRepository,
        // Repository
        private val megolmEncryptionFactory: MXMegolmEncryptionFactory,
        private val olmEncryptionFactory: MXOlmEncryptionFactory,
        private val deleteDeviceTask: DeleteDeviceTask,
        private val deleteDeviceWithUserPasswordTask: DeleteDeviceWithUserPasswordTask,
        // Tasks
        private val getDevicesTask: GetDevicesTask,
        private val getDeviceInfoTask: GetDeviceInfoTask,
        private val setDeviceNameTask: SetDeviceNameTask,
        private val uploadKeysTask: UploadKeysTask,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val monarchy: Monarchy,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val taskExecutor: TaskExecutor,
        private val cryptoCoroutineScope: CoroutineScope
) : CryptoService {

    init {
        verificationService.cryptoService = this
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    // MXEncrypting instance for each room.
    private val roomEncryptors: MutableMap<String, IMXEncrypting> = HashMap()
    private val isStarting = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)

    fun onStateEvent(roomId: String, event: Event) {
        when {
            event.getClearType() == EventType.STATE_ROOM_ENCRYPTION         -> onRoomEncryptionEvent(roomId, event)
            event.getClearType() == EventType.STATE_ROOM_MEMBER             -> onRoomMembershipEvent(roomId, event)
            event.getClearType() == EventType.STATE_ROOM_HISTORY_VISIBILITY -> onRoomHistoryVisibilityEvent(roomId, event)
        }
    }

    fun onLiveEvent(roomId: String, event: Event) {
        when {
            event.getClearType() == EventType.STATE_ROOM_ENCRYPTION         -> onRoomEncryptionEvent(roomId, event)
            event.getClearType() == EventType.STATE_ROOM_MEMBER             -> onRoomMembershipEvent(roomId, event)
            event.getClearType() == EventType.STATE_ROOM_HISTORY_VISIBILITY -> onRoomHistoryVisibilityEvent(roomId, event)
        }
    }

    override fun setDeviceName(deviceId: String, deviceName: String, callback: MatrixCallback<Unit>) {
        setDeviceNameTask
                .configureWith(SetDeviceNameTask.Params(deviceId, deviceName)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = object : MatrixCallback<Unit> {
                        override fun onSuccess(data: Unit) {
                            // bg refresh of crypto device
                            downloadKeys(listOf(credentials.userId), true, NoOpMatrixCallback())
                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            callback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun deleteDevice(deviceId: String, callback: MatrixCallback<Unit>) {
        deleteDeviceTask
                .configureWith(DeleteDeviceTask.Params(deviceId)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun deleteDeviceWithUserPassword(deviceId: String, authSession: String?, password: String, callback: MatrixCallback<Unit>) {
        deleteDeviceWithUserPasswordTask
                .configureWith(DeleteDeviceWithUserPasswordTask.Params(deviceId, authSession, password)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getCryptoVersion(context: Context, longFormat: Boolean): String {
        return if (longFormat) olmManager.getDetailedVersion(context) else olmManager.version
    }

    override fun getMyDevice(): CryptoDeviceInfo {
        return myDeviceInfoHolder.get().myDevice
    }

    override fun getDevicesList(callback: MatrixCallback<DevicesListResponse>) {
        getDevicesTask
                .configureWith {
//                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getDeviceInfo(deviceId: String, callback: MatrixCallback<DeviceInfo>) {
        getDeviceInfoTask
                .configureWith(GetDeviceInfoTask.Params(deviceId)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun inboundGroupSessionsCount(onlyBackedUp: Boolean): Int {
        return cryptoStore.inboundGroupSessionsCount(onlyBackedUp)
    }

    /**
     * Provides the tracking status
     *
     * @param userId the user id
     * @return the tracking status
     */
    override fun getDeviceTrackingStatus(userId: String): Int {
        return cryptoStore.getDeviceTrackingStatus(userId, DeviceListManager.TRACKING_STATUS_NOT_TRACKED)
    }

    /**
     * Tell if the MXCrypto is started
     *
     * @return true if the crypto is started
     */
    fun isStarted(): Boolean {
        return isStarted.get()
    }

    /**
     * Tells if the MXCrypto is starting.
     *
     * @return true if the crypto is starting
     */
    fun isStarting(): Boolean {
        return isStarting.get()
    }

    /**
     * Start the crypto module.
     * Device keys will be uploaded, then one time keys if there are not enough on the homeserver
     * and, then, if this is the first time, this new device will be announced to all other users
     * devices.
     *
     * @param isInitialSync true if it starts from an initial sync
     */
    fun start(isInitialSync: Boolean) {
        if (isStarted.get() || isStarting.get()) {
            return
        }
        isStarting.set(true)

        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            internalStart(isInitialSync)
        }
    }

    private suspend fun internalStart(isInitialSync: Boolean) {
        // Open the store
        cryptoStore.open()
        runCatching {
            uploadDeviceKeys()
            oneTimeKeysUploader.maybeUploadOneTimeKeys()
            keysBackupService.checkAndStartKeysBackup()
            if (isInitialSync) {
                // refresh the devices list for each known room members
                deviceListManager.invalidateAllDeviceLists()
                deviceListManager.refreshOutdatedDeviceLists()
            } else {
                incomingGossipingRequestManager.processReceivedGossipingRequests()
            }
        }.fold(
                {
                    isStarting.set(false)
                    isStarted.set(true)
                },
                {
                    isStarting.set(false)
                    isStarted.set(false)
                    Timber.e(it, "Start failed")
                }
        )
    }

    /**
     * Close the crypto
     */
    fun close() = runBlocking(coroutineDispatchers.crypto) {
        cryptoCoroutineScope.coroutineContext.cancelChildren(CancellationException("Closing crypto module"))

        olmDevice.release()
        cryptoStore.close()
    }

    // Aways enabled on RiotX
    override fun isCryptoEnabled() = true

    /**
     * @return the Keys backup Service
     */
    override fun keysBackupService() = keysBackupService

    /**
     * @return the VerificationService
     */
    override fun verificationService() = verificationService

    override fun crossSigningService() = crossSigningService

    /**
     * A sync response has been received
     *
     * @param syncResponse the syncResponse
     */
    fun onSyncCompleted(syncResponse: SyncResponse) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            runCatching {
                if (syncResponse.deviceLists != null) {
                    deviceListManager.handleDeviceListsChanges(syncResponse.deviceLists.changed, syncResponse.deviceLists.left)
                }
                if (syncResponse.deviceOneTimeKeysCount != null) {
                    val currentCount = syncResponse.deviceOneTimeKeysCount.signedCurve25519 ?: 0
                    oneTimeKeysUploader.updateOneTimeKeyCount(currentCount)
                }
                if (isStarted()) {
                    // Make sure we process to-device messages before generating new one-time-keys #2782
                    deviceListManager.refreshOutdatedDeviceLists()
                    oneTimeKeysUploader.maybeUploadOneTimeKeys()
                    incomingGossipingRequestManager.processReceivedGossipingRequests()
                }
            }
        }
    }

    /**
     * Find a device by curve25519 identity key
     *
     * @param senderKey the curve25519 key to match.
     * @param algorithm the encryption algorithm.
     * @return the device info, or null if not found / unsupported algorithm / crypto released
     */
    override fun deviceWithIdentityKey(senderKey: String, algorithm: String): CryptoDeviceInfo? {
        return if (algorithm != MXCRYPTO_ALGORITHM_MEGOLM && algorithm != MXCRYPTO_ALGORITHM_OLM) {
            // We only deal in olm keys
            null
        } else cryptoStore.deviceWithIdentityKey(senderKey)
    }

    /**
     * Provides the device information for a device id and a user Id
     *
     * @param userId   the user id
     * @param deviceId the device id
     */
    override fun getDeviceInfo(userId: String, deviceId: String?): CryptoDeviceInfo? {
        return if (userId.isNotEmpty() && !deviceId.isNullOrEmpty()) {
            cryptoStore.getUserDevice(userId, deviceId)
        } else {
            null
        }
    }

    override fun getCryptoDeviceInfo(userId: String): List<CryptoDeviceInfo> {
        return cryptoStore.getUserDevices(userId)?.map { it.value } ?: emptyList()
    }

    override fun getLiveCryptoDeviceInfo(): LiveData<List<CryptoDeviceInfo>> {
        return cryptoStore.getLiveDeviceList()
    }

    override fun getLiveCryptoDeviceInfo(userId: String): LiveData<List<CryptoDeviceInfo>> {
        return cryptoStore.getLiveDeviceList(userId)
    }

    override fun getLiveCryptoDeviceInfo(userIds: List<String>): LiveData<List<CryptoDeviceInfo>> {
        return cryptoStore.getLiveDeviceList(userIds)
    }

    /**
     * Set the devices as known
     *
     * @param devices  the devices. Note that the verified member of the devices in this list will not be updated by this method.
     * @param callback the asynchronous callback
     */
    override fun setDevicesKnown(devices: List<MXDeviceInfo>, callback: MatrixCallback<Unit>?) {
        // build a devices map
        val devicesIdListByUserId = devices.groupBy({ it.userId }, { it.deviceId })

        for ((userId, deviceIds) in devicesIdListByUserId) {
            val storedDeviceIDs = cryptoStore.getUserDevices(userId)

            // sanity checks
            if (null != storedDeviceIDs) {
                var isUpdated = false

                deviceIds.forEach { deviceId ->
                    val device = storedDeviceIDs[deviceId]

                    // assume if the device is either verified or blocked
                    // it means that the device is known
                    if (device?.isUnknown == true) {
                        device.trustLevel = DeviceTrustLevel(crossSigningVerified = false, locallyVerified = false)
                        isUpdated = true
                    }
                }

                if (isUpdated) {
                    cryptoStore.storeUserDevices(userId, storedDeviceIDs)
                }
            }
        }

        callback?.onSuccess(Unit)
    }

    /**
     * Update the blocked/verified state of the given device.
     *
     * @param trustLevel the new trust level
     * @param userId     the owner of the device
     * @param deviceId   the unique identifier for the device.
     */
    override fun setDeviceVerification(trustLevel: DeviceTrustLevel, userId: String, deviceId: String) {
        setDeviceVerificationAction.handle(trustLevel, userId, deviceId)
    }

    /**
     * Configure a room to use encryption.
     *
     * @param roomId             the room id to enable encryption in.
     * @param algorithm          the encryption config for the room.
     * @param inhibitDeviceQuery true to suppress device list query for users in the room (for now)
     * @param membersId          list of members to start tracking their devices
     * @return true if the operation succeeds.
     */
    private suspend fun setEncryptionInRoom(roomId: String,
                                            algorithm: String?,
                                            inhibitDeviceQuery: Boolean,
                                            membersId: List<String>): Boolean {
        // If we already have encryption in this room, we should ignore this event
        // (for now at least. Maybe we should alert the user somehow?)
        val existingAlgorithm = cryptoStore.getRoomAlgorithm(roomId)

        if (!existingAlgorithm.isNullOrEmpty() && existingAlgorithm != algorithm) {
            Timber.e("## setEncryptionInRoom() : Ignoring m.room.encryption event which requests a change of config in $roomId")
            return false
        }

        val encryptingClass = MXCryptoAlgorithms.hasEncryptorClassForAlgorithm(algorithm)

        if (!encryptingClass) {
            Timber.e("## setEncryptionInRoom() : Unable to encrypt room $roomId with $algorithm")
            return false
        }

        cryptoStore.storeRoomAlgorithm(roomId, algorithm!!)

        val alg: IMXEncrypting = when (algorithm) {
            MXCRYPTO_ALGORITHM_MEGOLM -> megolmEncryptionFactory.create(roomId)
            else                      -> olmEncryptionFactory.create(roomId)
        }

        synchronized(roomEncryptors) {
            roomEncryptors.put(roomId, alg)
        }

        // if encryption was not previously enabled in this room, we will have been
        // ignoring new device events for these users so far. We may well have
        // up-to-date lists for some users, for instance if we were sharing other
        // e2e rooms with them, so there is room for optimisation here, but for now
        // we just invalidate everyone in the room.
        if (null == existingAlgorithm) {
            Timber.v("Enabling encryption in $roomId for the first time; invalidating device lists for all users therein")

            val userIds = ArrayList(membersId)

            deviceListManager.startTrackingDeviceList(userIds)

            if (!inhibitDeviceQuery) {
                deviceListManager.refreshOutdatedDeviceLists()
            }
        }

        return true
    }

    /**
     * Tells if a room is encrypted with MXCRYPTO_ALGORITHM_MEGOLM
     *
     * @param roomId the room id
     * @return true if the room is encrypted with algorithm MXCRYPTO_ALGORITHM_MEGOLM
     */
    override fun isRoomEncrypted(roomId: String): Boolean {
        val encryptionEvent = monarchy.fetchCopied { realm ->
            EventEntity.whereType(realm, roomId = roomId, type = EventType.STATE_ROOM_ENCRYPTION)
                    .contains(EventEntityFields.CONTENT, "\"algorithm\":\"$MXCRYPTO_ALGORITHM_MEGOLM\"")
                    .findFirst()
        }
        return encryptionEvent != null
    }

    /**
     * @return the stored device keys for a user.
     */
    override fun getUserDevices(userId: String): MutableList<CryptoDeviceInfo> {
        return cryptoStore.getUserDevices(userId)?.values?.toMutableList() ?: ArrayList()
    }

    private fun isEncryptionEnabledForInvitedUser(): Boolean {
        return mxCryptoConfig.enableEncryptionForInvitedMembers
    }

    override fun getEncryptionAlgorithm(roomId: String): String? {
        return cryptoStore.getRoomAlgorithm(roomId)
    }

    /**
     * Determine whether we should encrypt messages for invited users in this room.
     * <p>
     * Check here whether the invited members are allowed to read messages in the room history
     * from the point they were invited onwards.
     *
     * @return true if we should encrypt messages for invited users.
     */
    override fun shouldEncryptForInvitedMembers(roomId: String): Boolean {
        return cryptoStore.shouldEncryptForInvitedMembers(roomId)
    }

    /**
     * Encrypt an event content according to the configuration of the room.
     *
     * @param eventContent the content of the event.
     * @param eventType    the type of the event.
     * @param roomId       the room identifier the event will be sent.
     * @param callback     the asynchronous callback
     */
    override fun encryptEventContent(eventContent: Content,
                                     eventType: String,
                                     roomId: String,
                                     callback: MatrixCallback<MXEncryptEventContentResult>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            if (!isStarted()) {
                Timber.v("## encryptEventContent() : wait after e2e init")
                internalStart(false)
            }
            val userIds = getRoomUserIds(roomId)
            var alg = synchronized(roomEncryptors) {
                roomEncryptors[roomId]
            }
            if (alg == null) {
                val algorithm = getEncryptionAlgorithm(roomId)
                if (algorithm != null) {
                    if (setEncryptionInRoom(roomId, algorithm, false, userIds)) {
                        synchronized(roomEncryptors) {
                            alg = roomEncryptors[roomId]
                        }
                    }
                }
            }
            val safeAlgorithm = alg
            if (safeAlgorithm != null) {
                val t0 = System.currentTimeMillis()
                Timber.v("## encryptEventContent() starts")
                runCatching {
                    val content = safeAlgorithm.encryptEventContent(eventContent, eventType, userIds)
                    Timber.v("## encryptEventContent() : succeeds after ${System.currentTimeMillis() - t0} ms")
                    MXEncryptEventContentResult(content, EventType.ENCRYPTED)
                }.foldToCallback(callback)
            } else {
                val algorithm = getEncryptionAlgorithm(roomId)
                val reason = String.format(MXCryptoError.UNABLE_TO_ENCRYPT_REASON,
                        algorithm ?: MXCryptoError.NO_MORE_ALGORITHM_REASON)
                Timber.e("## encryptEventContent() : $reason")
                callback.onFailure(Failure.CryptoError(MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_ENCRYPT, reason)))
            }
        }
    }

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or throw in case of error
     */
    @Throws(MXCryptoError::class)
    override fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
       return internalDecryptEvent(event, timeline)
    }

    /**
     * Decrypt an event asynchronously
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @param callback the callback to return data or null
     */
    override fun decryptEventAsync(event: Event, timeline: String, callback: MatrixCallback<MXEventDecryptionResult>) {
        cryptoCoroutineScope.launch {
            val result = runCatching {
                withContext(coroutineDispatchers.crypto) {
                    internalDecryptEvent(event, timeline)
                }
            }
            result.foldToCallback(callback)
        }
    }

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or null in case of error
     */
    private fun internalDecryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
        val eventContent = event.content
        if (eventContent == null) {
            Timber.e("## decryptEvent : empty event content")
            throw MXCryptoError.Base(MXCryptoError.ErrorType.BAD_ENCRYPTED_MESSAGE, MXCryptoError.BAD_ENCRYPTED_MESSAGE_REASON)
        } else {
            val algorithm = eventContent["algorithm"]?.toString()
            val alg = roomDecryptorProvider.getOrCreateRoomDecryptor(event.roomId, algorithm)
            if (alg == null) {
                val reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, event.eventId, algorithm)
                Timber.e("## decryptEvent() : $reason")
                throw MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_DECRYPT, reason)
            } else {
                return alg.decryptEvent(event, timeline)
            }
        }
    }

    /**
     * Reset replay attack data for the given timeline.
     *
     * @param timelineId the timeline id
     */
    fun resetReplayAttackCheckInTimeline(timelineId: String) {
        olmDevice.resetReplayAttackCheckInTimeline(timelineId)
    }

    /**
     * Handle the 'toDevice' event
     *
     * @param event the event
     */
    fun onToDeviceEvent(event: Event) {
        // event have already been decrypted
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            when (event.getClearType()) {
                EventType.ROOM_KEY, EventType.FORWARDED_ROOM_KEY -> {
                    cryptoStore.saveGossipingEvent(event)
                    // Keys are imported directly, not waiting for end of sync
                    onRoomKeyEvent(event)
                }
                EventType.REQUEST_SECRET,
                EventType.ROOM_KEY_REQUEST                       -> {
                    // save audit trail
                    cryptoStore.saveGossipingEvent(event)
                    // Requests are stacked, and will be handled one by one at the end of the sync (onSyncComplete)
                    incomingGossipingRequestManager.onGossipingRequestEvent(event)
                }
                EventType.SEND_SECRET                            -> {
                    cryptoStore.saveGossipingEvent(event)
                    onSecretSendReceived(event)
                }
                else                                             -> {
                    // ignore
                }
            }
        }
    }

    /**
     * Handle a key event.
     *
     * @param event the key event.
     */
    private fun onRoomKeyEvent(event: Event) {
        val roomKeyContent = event.getClearContent().toModel<RoomKeyContent>() ?: return
        Timber.v("## GOSSIP onRoomKeyEvent() : type<${event.type}> , sessionId<${roomKeyContent.sessionId}>")
        if (roomKeyContent.roomId.isNullOrEmpty() || roomKeyContent.algorithm.isNullOrEmpty()) {
            Timber.e("## GOSSIP onRoomKeyEvent() : missing fields")
            return
        }
        val alg = roomDecryptorProvider.getOrCreateRoomDecryptor(roomKeyContent.roomId, roomKeyContent.algorithm)
        if (alg == null) {
            Timber.e("## GOSSIP onRoomKeyEvent() : Unable to handle keys for ${roomKeyContent.algorithm}")
            return
        }
        alg.onRoomKeyEvent(event, keysBackupService)
    }

    private fun onSecretSendReceived(event: Event) {
        Timber.i("## GOSSIP onSecretSend() : onSecretSendReceived ${event.content?.get("sender_key")}")
        if (!event.isEncrypted()) {
            // secret send messages must be encrypted
            Timber.e("## GOSSIP onSecretSend() :Received unencrypted secret send event")
            return
        }

        // Was that sent by us?
        if (event.senderId != credentials.userId) {
            Timber.e("## GOSSIP onSecretSend() : Ignore secret from other user ${event.senderId}")
            return
        }

        val secretContent = event.getClearContent().toModel<SecretSendEventContent>() ?: return

        val existingRequest = cryptoStore
                .getOutgoingSecretKeyRequests().firstOrNull { it.requestId == secretContent.requestId }

        if (existingRequest == null) {
            Timber.i("## GOSSIP onSecretSend() : Ignore secret that was not requested: ${secretContent.requestId}")
            return
        }

        when (existingRequest.secretName) {
            SELF_SIGNING_KEY_SSSS_NAME -> {
                    crossSigningService.onSecretSSKGossip(secretContent.secretValue)
                    return
            }
            USER_SIGNING_KEY_SSSS_NAME -> {
                    crossSigningService.onSecretUSKGossip(secretContent.secretValue)
                    return
            }
            KEYBACKUP_SECRET_SSSS_NAME -> {
                keysBackupService.onSecretKeyGossip(secretContent.secretValue)
                return
            }
            else                       -> {
                // Ask to application layer?
                Timber.v("## onSecretSend() : secret not handled by SDK")
            }
        }
    }

    /**
     * Handle an m.room.encryption event.
     *
     * @param event the encryption event.
     */
    private fun onRoomEncryptionEvent(roomId: String, event: Event) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            val params = LoadRoomMembersTask.Params(roomId)
            try {
                loadRoomMembersTask.execute(params)
                val userIds = getRoomUserIds(roomId)
                setEncryptionInRoom(roomId, event.content?.get("algorithm")?.toString(), true, userIds)
            } catch (throwable: Throwable) {
                Timber.e(throwable, "## onRoomEncryptionEvent ERROR FAILED TO SETUP CRYPTO ")
            }
        }
    }

    private fun getRoomUserIds(roomId: String): List<String> {
        var userIds: List<String> = emptyList()
        monarchy.doWithRealm { realm ->
            // Check whether the event content must be encrypted for the invited members.
            val encryptForInvitedMembers = isEncryptionEnabledForInvitedUser()
                    && shouldEncryptForInvitedMembers(roomId)

            userIds = if (encryptForInvitedMembers) {
                RoomMemberHelper(realm, roomId).getActiveRoomMemberIds()
            } else {
                RoomMemberHelper(realm, roomId).getJoinedRoomMemberIds()
            }
        }
        return userIds
    }

    /**
     * Handle a change in the membership state of a member of a room.
     *
     * @param event the membership event causing the change
     */
    private fun onRoomMembershipEvent(roomId: String, event: Event) {
        val alg: IMXEncrypting?

        synchronized(roomEncryptors) {
            alg = roomEncryptors[roomId]
        }

        if (null == alg) {
            // No encrypting in this room
            return
        }
        event.stateKey?.let { userId ->
            val roomMember: RoomMemberSummary? = event.content.toModel()
            val membership = roomMember?.membership
            if (membership == Membership.JOIN) {
                // make sure we are tracking the deviceList for this user.
                deviceListManager.startTrackingDeviceList(listOf(userId))
            } else if (membership == Membership.INVITE
                    && shouldEncryptForInvitedMembers(roomId)
                    && isEncryptionEnabledForInvitedUser()) {
                // track the deviceList for this invited user.
                // Caution: there's a big edge case here in that federated servers do not
                // know what other servers are in the room at the time they've been invited.
                // They therefore will not send device updates if a user logs in whilst
                // their state is invite.
                deviceListManager.startTrackingDeviceList(listOf(userId))
            }
        }
    }

    private fun onRoomHistoryVisibilityEvent(roomId: String, event: Event) {
        val eventContent = event.content.toModel<RoomHistoryVisibilityContent>()
        eventContent?.historyVisibility?.let {
            cryptoStore.setShouldEncryptForInvitedMembers(roomId, it != RoomHistoryVisibility.JOINED)
        }
    }

    /**
     * Upload my user's device keys.
     */
    private suspend fun uploadDeviceKeys(): KeysUploadResponse {
        // Prepare the device keys data to send
        // Sign it
        val canonicalJson = JsonCanonicalizer.getCanonicalJson(Map::class.java, getMyDevice().signalableJSONDictionary())
        var rest = getMyDevice().toRest()

        rest = rest.copy(
                signatures = objectSigner.signObject(canonicalJson)
        )

        // For now, we set the device id explicitly, as we may not be using the
        // same one as used in login.
        val uploadDeviceKeysParams = UploadKeysTask.Params(rest, null, getMyDevice().deviceId)
        return uploadKeysTask.execute(uploadDeviceKeysParams)
    }

    /**
     * Export the crypto keys
     *
     * @param password the password
     * @param callback the exported keys
     */
    override fun exportRoomKeys(password: String, callback: MatrixCallback<ByteArray>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                exportRoomKeys(password, MXMegolmExportEncryption.DEFAULT_ITERATION_COUNT)
            }.foldToCallback(callback)
        }
    }

    /**
     * Export the crypto keys
     *
     * @param password         the password
     * @param anIterationCount the encryption iteration count (0 means no encryption)
     */
    private suspend fun exportRoomKeys(password: String, anIterationCount: Int): ByteArray {
        return withContext(coroutineDispatchers.crypto) {
            val iterationCount = max(0, anIterationCount)

            val exportedSessions = cryptoStore.getInboundGroupSessions().mapNotNull { it.exportKeys() }

            val adapter = MoshiProvider.providesMoshi()
                    .adapter(List::class.java)

            MXMegolmExportEncryption.encryptMegolmKeyFile(adapter.toJson(exportedSessions), password, iterationCount)
        }
    }

    /**
     * Import the room keys
     *
     * @param roomKeysAsArray  the room keys as array.
     * @param password         the password
     * @param progressListener the progress listener
     * @param callback         the asynchronous callback.
     */
    override fun importRoomKeys(roomKeysAsArray: ByteArray,
                                password: String,
                                progressListener: ProgressListener?,
                                callback: MatrixCallback<ImportRoomKeysResult>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.main) {
            runCatching {
                withContext(coroutineDispatchers.crypto) {
                    Timber.v("## importRoomKeys starts")

                    val t0 = System.currentTimeMillis()
                    val roomKeys = MXMegolmExportEncryption.decryptMegolmKeyFile(roomKeysAsArray, password)
                    val t1 = System.currentTimeMillis()

                    Timber.v("## importRoomKeys : decryptMegolmKeyFile done in ${t1 - t0} ms")

                    val importedSessions = MoshiProvider.providesMoshi()
                            .adapter<List<MegolmSessionData>>(Types.newParameterizedType(List::class.java, MegolmSessionData::class.java))
                            .fromJson(roomKeys)

                    val t2 = System.currentTimeMillis()

                    Timber.v("## importRoomKeys : JSON parsing ${t2 - t1} ms")

                    if (importedSessions == null) {
                        throw Exception("Error")
                    }

                    megolmSessionDataImporter.handle(importedSessions, true, progressListener)
                }
            }.foldToCallback(callback)
        }
    }

    /**
     * Update the warn status when some unknown devices are detected.
     *
     * @param warn true to warn when some unknown devices are detected.
     */
    override fun setWarnOnUnknownDevices(warn: Boolean) {
        warnOnUnknownDevicesRepository.setWarnOnUnknownDevices(warn)
    }

    /**
     * Check if the user ids list have some unknown devices.
     * A success means there is no unknown devices.
     * If there are some unknown devices, a MXCryptoError.UnknownDevice exception is triggered.
     *
     * @param userIds  the user ids list
     * @param callback the asynchronous callback.
     */
    fun checkUnknownDevices(userIds: List<String>, callback: MatrixCallback<Unit>) {
        // force the refresh to ensure that the devices list is up-to-date
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            runCatching {
                val keys = deviceListManager.downloadKeys(userIds, true)
                val unknownDevices = getUnknownDevices(keys)
                if (unknownDevices.map.isNotEmpty()) {
                    // trigger an an unknown devices exception
                    throw Failure.CryptoError(MXCryptoError.UnknownDevice(unknownDevices))
                }
            }.foldToCallback(callback)
        }
    }

    /**
     * Set the global override for whether the client should ever send encrypted
     * messages to unverified devices.
     * If false, it can still be overridden per-room.
     * If true, it overrides the per-room settings.
     *
     * @param block    true to unilaterally blacklist all
     */
    override fun setGlobalBlacklistUnverifiedDevices(block: Boolean) {
        cryptoStore.setGlobalBlacklistUnverifiedDevices(block)
    }

    /**
     * Tells whether the client should ever send encrypted messages to unverified devices.
     * The default value is false.
     * This function must be called in the getEncryptingThreadHandler() thread.
     *
     * @return true to unilaterally blacklist all unverified devices.
     */
    override fun getGlobalBlacklistUnverifiedDevices(): Boolean {
        return cryptoStore.getGlobalBlacklistUnverifiedDevices()
    }

    /**
     * Tells whether the client should encrypt messages only for the verified devices
     * in this room.
     * The default value is false.
     *
     * @param roomId the room id
     * @return true if the client should encrypt messages only for the verified devices.
     */
// TODO add this info in CryptoRoomEntity?
    override fun isRoomBlacklistUnverifiedDevices(roomId: String?): Boolean {
        return roomId?.let { cryptoStore.getRoomsListBlacklistUnverifiedDevices().contains(it) }
                ?: false
    }

    /**
     * Manages the room black-listing for unverified devices.
     *
     * @param roomId   the room id
     * @param add      true to add the room id to the list, false to remove it.
     */
    private fun setRoomBlacklistUnverifiedDevices(roomId: String, add: Boolean) {
        val roomIds = cryptoStore.getRoomsListBlacklistUnverifiedDevices().toMutableList()

        if (add) {
            if (roomId !in roomIds) {
                roomIds.add(roomId)
            }
        } else {
            roomIds.remove(roomId)
        }

        cryptoStore.setRoomsListBlacklistUnverifiedDevices(roomIds)
    }

    /**
     * Add this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     */
    override fun setRoomBlacklistUnverifiedDevices(roomId: String) {
        setRoomBlacklistUnverifiedDevices(roomId, true)
    }

    /**
     * Remove this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     */
    override fun setRoomUnBlacklistUnverifiedDevices(roomId: String) {
        setRoomBlacklistUnverifiedDevices(roomId, false)
    }

// TODO Check if this method is still necessary
    /**
     * Cancel any earlier room key request
     *
     * @param requestBody requestBody
     */
    override fun cancelRoomKeyRequest(requestBody: RoomKeyRequestBody) {
        outgoingGossipingRequestManager.cancelRoomKeyRequest(requestBody)
    }

    /**
     * Re request the encryption keys required to decrypt an event.
     *
     * @param event the event to decrypt again.
     */
    override fun reRequestRoomKeyForEvent(event: Event) {
        val wireContent = event.content.toModel<EncryptedEventContent>() ?: return Unit.also {
            Timber.e("## reRequestRoomKeyForEvent Failed to re-request key, null content")
        }

        val requestBody = RoomKeyRequestBody(
                algorithm = wireContent.algorithm,
                roomId = event.roomId,
                senderKey = wireContent.senderKey,
                sessionId = wireContent.sessionId
        )

        outgoingGossipingRequestManager.resendRoomKeyRequest(requestBody)
    }

    override fun requestRoomKeyForEvent(event: Event) {
        val wireContent = event.content.toModel<EncryptedEventContent>() ?: return Unit.also {
            Timber.e("## requestRoomKeyForEvent Failed to request key, null content eventId: ${event.eventId}")
        }

        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            if (!isStarted()) {
                Timber.v("## requestRoomKeyForEvent() : wait after e2e init")
                internalStart(false)
            }
            roomDecryptorProvider
                    .getOrCreateRoomDecryptor(event.roomId, wireContent.algorithm)
                    ?.requestKeysForEvent(event) ?: run {
                Timber.v("## requestRoomKeyForEvent() : No room decryptor for roomId:${event.roomId} algorithm:${wireContent.algorithm}")
            }
        }
    }

    /**
     * Add a GossipingRequestListener listener.
     *
     * @param listener listener
     */
    override fun addRoomKeysRequestListener(listener: GossipingRequestListener) {
        incomingGossipingRequestManager.addRoomKeysRequestListener(listener)
    }

    /**
     * Add a GossipingRequestListener listener.
     *
     * @param listener listener
     */
    override fun removeRoomKeysRequestListener(listener: GossipingRequestListener) {
        incomingGossipingRequestManager.removeRoomKeysRequestListener(listener)
    }

    /**
     * Provides the list of unknown devices
     *
     * @param devicesInRoom the devices map
     * @return the unknown devices map
     */
    private fun getUnknownDevices(devicesInRoom: MXUsersDevicesMap<CryptoDeviceInfo>): MXUsersDevicesMap<CryptoDeviceInfo> {
        val unknownDevices = MXUsersDevicesMap<CryptoDeviceInfo>()
        val userIds = devicesInRoom.userIds
        for (userId in userIds) {
            devicesInRoom.getUserDeviceIds(userId)?.forEach { deviceId ->
                devicesInRoom.getObject(userId, deviceId)
                        ?.takeIf { it.isUnknown }
                        ?.let {
                            unknownDevices.setObject(userId, deviceId, it)
                        }
            }
        }

        return unknownDevices
    }

    override fun downloadKeys(userIds: List<String>, forceDownload: Boolean, callback: MatrixCallback<MXUsersDevicesMap<CryptoDeviceInfo>>) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            runCatching {
                deviceListManager.downloadKeys(userIds, forceDownload)
            }.foldToCallback(callback)
        }
    }

    override fun addNewSessionListener(newSessionListener: NewSessionListener) {
        roomDecryptorProvider.addNewSessionListener(newSessionListener)
    }

    override fun removeSessionListener(listener: NewSessionListener) {
        roomDecryptorProvider.removeSessionListener(listener)
    }
/* ==========================================================================================
 * DEBUG INFO
 * ========================================================================================== */

    override fun toString(): String {
        return "DefaultCryptoService of " + credentials.userId + " (" + credentials.deviceId + ")"
    }

    override fun getOutgoingRoomKeyRequest(): List<OutgoingRoomKeyRequest> {
        return cryptoStore.getOutgoingRoomKeyRequests()
    }

    override fun getIncomingRoomKeyRequest(): List<IncomingRoomKeyRequest> {
        return cryptoStore.getIncomingRoomKeyRequests()
    }

    override fun getGossipingEventsTrail(): List<Event> {
        return cryptoStore.getGossipingEventsTrail()
    }
}

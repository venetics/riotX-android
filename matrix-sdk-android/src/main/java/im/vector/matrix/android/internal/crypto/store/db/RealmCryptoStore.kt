/*
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

package im.vector.matrix.android.internal.crypto.store.db

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.session.crypto.crosssigning.MXCrossSigningInfo
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.LocalEcho
import im.vector.matrix.android.api.session.room.send.SendState
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.api.util.toOptional
import im.vector.matrix.android.internal.crypto.GossipRequestType
import im.vector.matrix.android.internal.crypto.GossipingRequestState
import im.vector.matrix.android.internal.crypto.IncomingRoomKeyRequest
import im.vector.matrix.android.internal.crypto.IncomingSecretShareRequest
import im.vector.matrix.android.internal.crypto.IncomingShareRequestCommon
import im.vector.matrix.android.internal.crypto.NewSessionListener
import im.vector.matrix.android.internal.crypto.OutgoingGossipingRequestState
import im.vector.matrix.android.internal.crypto.OutgoingRoomKeyRequest
import im.vector.matrix.android.internal.crypto.OutgoingSecretRequest
import im.vector.matrix.android.internal.crypto.algorithms.olm.OlmDecryptionResult
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import im.vector.matrix.android.internal.crypto.model.CryptoCrossSigningKey
import im.vector.matrix.android.internal.crypto.model.CryptoDeviceInfo
import im.vector.matrix.android.internal.crypto.model.OlmInboundGroupSessionWrapper
import im.vector.matrix.android.internal.crypto.model.OlmSessionWrapper
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyRequestBody
import im.vector.matrix.android.internal.crypto.model.toEntity
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import im.vector.matrix.android.internal.crypto.store.PrivateKeysInfo
import im.vector.matrix.android.internal.crypto.store.SavedKeyBackupKeyInfo
import im.vector.matrix.android.internal.crypto.store.db.model.CrossSigningInfoEntity
import im.vector.matrix.android.internal.crypto.store.db.model.CrossSigningInfoEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.CryptoMapper
import im.vector.matrix.android.internal.crypto.store.db.model.CryptoMetadataEntity
import im.vector.matrix.android.internal.crypto.store.db.model.CryptoRoomEntity
import im.vector.matrix.android.internal.crypto.store.db.model.CryptoRoomEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.DeviceInfoEntity
import im.vector.matrix.android.internal.crypto.store.db.model.DeviceInfoEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.GossipingEventEntity
import im.vector.matrix.android.internal.crypto.store.db.model.IncomingGossipingRequestEntity
import im.vector.matrix.android.internal.crypto.store.db.model.IncomingGossipingRequestEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.KeyInfoEntity
import im.vector.matrix.android.internal.crypto.store.db.model.KeysBackupDataEntity
import im.vector.matrix.android.internal.crypto.store.db.model.OlmInboundGroupSessionEntity
import im.vector.matrix.android.internal.crypto.store.db.model.OlmInboundGroupSessionEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.OlmSessionEntity
import im.vector.matrix.android.internal.crypto.store.db.model.OlmSessionEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.OutgoingGossipingRequestEntity
import im.vector.matrix.android.internal.crypto.store.db.model.OutgoingGossipingRequestEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.TrustLevelEntity
import im.vector.matrix.android.internal.crypto.store.db.model.UserEntity
import im.vector.matrix.android.internal.crypto.store.db.model.UserEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.createPrimaryKey
import im.vector.matrix.android.internal.crypto.store.db.query.delete
import im.vector.matrix.android.internal.crypto.store.db.query.get
import im.vector.matrix.android.internal.crypto.store.db.query.getById
import im.vector.matrix.android.internal.crypto.store.db.query.getOrCreate
import im.vector.matrix.android.internal.database.mapper.ContentMapper
import im.vector.matrix.android.internal.di.CryptoDatabase
import im.vector.matrix.android.internal.di.MoshiProvider
import im.vector.matrix.android.internal.session.SessionScope
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmList
import io.realm.Sort
import io.realm.kotlin.where
import org.matrix.olm.OlmAccount
import org.matrix.olm.OlmException
import timber.log.Timber
import javax.inject.Inject
import kotlin.collections.set

@SessionScope
internal class RealmCryptoStore @Inject constructor(
        @CryptoDatabase private val realmConfiguration: RealmConfiguration,
        private val credentials: Credentials) : IMXCryptoStore {

    /* ==========================================================================================
     * Memory cache, to correctly release JNI objects
     * ========================================================================================== */

    // A realm instance, for faster future getInstance. Do not use it
    private var realmLocker: Realm? = null

    // The olm account
    private var olmAccount: OlmAccount? = null

    // Cache for OlmSession, to release them properly
    private val olmSessionsToRelease = HashMap<String, OlmSessionWrapper>()

    // Cache for InboundGroupSession, to release them properly
    private val inboundGroupSessionToRelease = HashMap<String, OlmInboundGroupSessionWrapper>()

    private val newSessionListeners = ArrayList<NewSessionListener>()

    override fun addNewSessionListener(listener: NewSessionListener) {
        if (!newSessionListeners.contains(listener)) newSessionListeners.add(listener)
    }

    override fun removeSessionListener(listener: NewSessionListener) {
        newSessionListeners.remove(listener)
    }

    private val monarchy = Monarchy.Builder()
            .setRealmConfiguration(realmConfiguration)
            .build()

    init {
        // Ensure CryptoMetadataEntity is inserted in DB
        doRealmTransaction(realmConfiguration) { realm ->
            var currentMetadata = realm.where<CryptoMetadataEntity>().findFirst()

            var deleteAll = false

            if (currentMetadata != null) {
                // Check credentials
                // The device id may not have been provided in credentials.
                // Check it only if provided, else trust the stored one.
                if (currentMetadata.userId != credentials.userId
                        || (credentials.deviceId != null && credentials.deviceId != currentMetadata.deviceId)) {
                    Timber.w("## open() : Credentials do not match, close this store and delete data")
                    deleteAll = true
                    currentMetadata = null
                }
            }

            if (currentMetadata == null) {
                if (deleteAll) {
                    realm.deleteAll()
                }

                // Metadata not found, or database cleaned, create it
                realm.createObject(CryptoMetadataEntity::class.java, credentials.userId).apply {
                    deviceId = credentials.deviceId
                }
            }
        }
    }
    /* ==========================================================================================
     * Other data
     * ========================================================================================== */

    override fun hasData(): Boolean {
        return doWithRealm(realmConfiguration) {
            !it.isEmpty
                    // Check if there is a MetaData object
                    && it.where<CryptoMetadataEntity>().count() > 0
        }
    }

    override fun deleteStore() {
        doRealmTransaction(realmConfiguration) {
            it.deleteAll()
        }
    }

    override fun open() {
        realmLocker = Realm.getInstance(realmConfiguration)
    }

    override fun close() {
        olmSessionsToRelease.forEach {
            it.value.olmSession.releaseSession()
        }
        olmSessionsToRelease.clear()

        inboundGroupSessionToRelease.forEach {
            it.value.olmInboundGroupSession?.releaseSession()
        }
        inboundGroupSessionToRelease.clear()

        olmAccount?.releaseAccount()

        realmLocker?.close()
        realmLocker = null
    }

    override fun storeDeviceId(deviceId: String) {
        doRealmTransaction(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()?.deviceId = deviceId
        }
    }

    override fun getDeviceId(): String {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()
        }?.deviceId ?: ""
    }

    override fun saveOlmAccount() {
        doRealmTransaction(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()?.putOlmAccount(olmAccount)
        }
    }

    override fun getOlmAccount(): OlmAccount {
        return olmAccount!!
    }

    override fun getOrCreateOlmAccount(): OlmAccount {
        doRealmTransaction(realmConfiguration) {
            val metaData = it.where<CryptoMetadataEntity>().findFirst()
            val existing = metaData!!.getOlmAccount()
            if (existing == null) {
                Timber.d("## Crypto Creating olm account")
                val created = OlmAccount()
                metaData.putOlmAccount(created)
                olmAccount = created
            } else {
                Timber.d("## Crypto Access existing account")
                olmAccount = existing
            }
        }
        return olmAccount!!
    }

    override fun storeUserDevice(userId: String?, deviceInfo: CryptoDeviceInfo?) {
        if (userId == null || deviceInfo == null) {
            return
        }

        doRealmTransaction(realmConfiguration) { realm ->
            val user = UserEntity.getOrCreate(realm, userId)

            // Create device info
            val deviceInfoEntity = CryptoMapper.mapToEntity(deviceInfo)
            realm.insertOrUpdate(deviceInfoEntity)
//            val deviceInfoEntity = DeviceInfoEntity.getOrCreate(it, userId, deviceInfo.deviceId).apply {
//                deviceId = deviceInfo.deviceId
//                identityKey = deviceInfo.identityKey()
//                putDeviceInfo(deviceInfo)
//            }

            if (!user.devices.contains(deviceInfoEntity)) {
                user.devices.add(deviceInfoEntity)
            }
        }
    }

    override fun getUserDevice(userId: String, deviceId: String): CryptoDeviceInfo? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<DeviceInfoEntity>()
                    .equalTo(DeviceInfoEntityFields.PRIMARY_KEY, DeviceInfoEntity.createPrimaryKey(userId, deviceId))
                    .findFirst()
        }?.let {
            CryptoMapper.mapToModel(it)
        }
    }

    override fun deviceWithIdentityKey(identityKey: String): CryptoDeviceInfo? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<DeviceInfoEntity>()
                    .equalTo(DeviceInfoEntityFields.IDENTITY_KEY, identityKey)
                    .findFirst()
        }
                ?.let {
                    CryptoMapper.mapToModel(it)
                }
    }

    override fun storeUserDevices(userId: String, devices: Map<String, CryptoDeviceInfo>?) {
        doRealmTransaction(realmConfiguration) { realm ->
            if (devices == null) {
                // Remove the user
                UserEntity.delete(realm, userId)
            } else {
                UserEntity.getOrCreate(realm, userId)
                        .let { u ->
                            // Add the devices
                            // Ensure all other devices are deleted
                            u.devices.deleteAllFromRealm()
                            val new = devices.map { entry -> entry.value.toEntity() }
                            new.forEach { realm.insertOrUpdate(it) }
                            u.devices.addAll(new)
                        }
            }
        }
    }

    override fun storeUserCrossSigningKeys(userId: String,
                                           masterKey: CryptoCrossSigningKey?,
                                           selfSigningKey: CryptoCrossSigningKey?,
                                           userSigningKey: CryptoCrossSigningKey?) {
        doRealmTransaction(realmConfiguration) { realm ->
            UserEntity.getOrCreate(realm, userId)
                    .let { userEntity ->
                        if (masterKey == null || selfSigningKey == null) {
                            // The user has disabled cross signing?
                            userEntity.crossSigningInfoEntity?.deleteFromRealm()
                            userEntity.crossSigningInfoEntity = null
                        } else {
                            CrossSigningInfoEntity.getOrCreate(realm, userId).let { signingInfo ->
                                // What should we do if we detect a change of the keys?

                                val existingMaster = signingInfo.getMasterKey()
                                if (existingMaster != null && existingMaster.publicKeyBase64 == masterKey.unpaddedBase64PublicKey) {
                                    // update signatures?
                                    existingMaster.putSignatures(masterKey.signatures)
                                    existingMaster.usages = masterKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                            ?: RealmList()
                                } else {
                                    val keyEntity = realm.createObject(KeyInfoEntity::class.java).apply {
                                        this.publicKeyBase64 = masterKey.unpaddedBase64PublicKey
                                        this.usages = masterKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                                ?: RealmList()
                                        this.putSignatures(masterKey.signatures)
                                    }
                                    signingInfo.setMasterKey(keyEntity)
                                }

                                val existingSelfSigned = signingInfo.getSelfSignedKey()
                                if (existingSelfSigned != null && existingSelfSigned.publicKeyBase64 == selfSigningKey.unpaddedBase64PublicKey) {
                                    // update signatures?
                                    existingSelfSigned.putSignatures(selfSigningKey.signatures)
                                    existingSelfSigned.usages = selfSigningKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                            ?: RealmList()
                                } else {
                                    val keyEntity = realm.createObject(KeyInfoEntity::class.java).apply {
                                        this.publicKeyBase64 = selfSigningKey.unpaddedBase64PublicKey
                                        this.usages = selfSigningKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                                ?: RealmList()
                                        this.putSignatures(selfSigningKey.signatures)
                                    }
                                    signingInfo.setSelfSignedKey(keyEntity)
                                }

                                // Only for me
                                if (userSigningKey != null) {
                                    val existingUSK = signingInfo.getUserSigningKey()
                                    if (existingUSK != null && existingUSK.publicKeyBase64 == userSigningKey.unpaddedBase64PublicKey) {
                                        // update signatures?
                                        existingUSK.putSignatures(userSigningKey.signatures)
                                        existingUSK.usages = userSigningKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                                ?: RealmList()
                                    } else {
                                        val keyEntity = realm.createObject(KeyInfoEntity::class.java).apply {
                                            this.publicKeyBase64 = userSigningKey.unpaddedBase64PublicKey
                                            this.usages = userSigningKey.usages?.toTypedArray()?.let { RealmList(*it) }
                                                    ?: RealmList()
                                            this.putSignatures(userSigningKey.signatures)
                                        }
                                        signingInfo.setUserSignedKey(keyEntity)
                                    }
                                }

                                userEntity.crossSigningInfoEntity = signingInfo
                            }
                        }
                    }
        }
    }

    override fun getCrossSigningPrivateKeys(): PrivateKeysInfo? {
        return doRealmQueryAndCopy(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()
        }?.let {
            PrivateKeysInfo(
                    master = it.xSignMasterPrivateKey,
                    selfSigned = it.xSignSelfSignedPrivateKey,
                    user = it.xSignUserPrivateKey
            )
        }
    }

    override fun storePrivateKeysInfo(msk: String?, usk: String?, ssk: String?) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.apply {
                xSignMasterPrivateKey = msk
                xSignUserPrivateKey = usk
                xSignSelfSignedPrivateKey = ssk
            }
        }
    }

    override fun saveBackupRecoveryKey(recoveryKey: String?, version: String?) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.apply {
                keyBackupRecoveryKey = recoveryKey
                keyBackupRecoveryKeyVersion = version
            }
        }
    }

    override fun getKeyBackupRecoveryKeyInfo(): SavedKeyBackupKeyInfo? {
        return doRealmQueryAndCopy(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()
        }?.let {
            val key = it.keyBackupRecoveryKey
            val version = it.keyBackupRecoveryKeyVersion
            if (!key.isNullOrBlank() && !version.isNullOrBlank()) {
                SavedKeyBackupKeyInfo(recoveryKey = key, version = version)
            } else {
                null
            }
        }
    }

    override fun storeSSKPrivateKey(ssk: String?) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.apply {
                xSignSelfSignedPrivateKey = ssk
            }
        }
    }

    override fun storeUSKPrivateKey(usk: String?) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.apply {
                xSignUserPrivateKey = usk
            }
        }
    }

    override fun getUserDevices(userId: String): Map<String, CryptoDeviceInfo>? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<UserEntity>()
                    .equalTo(UserEntityFields.USER_ID, userId)
                    .findFirst()
        }
                ?.devices
                ?.map { CryptoMapper.mapToModel(it) }
                ?.associateBy { it.deviceId }
    }

    override fun getUserDeviceList(userId: String): List<CryptoDeviceInfo>? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<UserEntity>()
                    .equalTo(UserEntityFields.USER_ID, userId)
                    .findFirst()
        }
                ?.devices
                ?.map { CryptoMapper.mapToModel(it) }
    }

    override fun getLiveDeviceList(userId: String): LiveData<List<CryptoDeviceInfo>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm: Realm ->
                    realm
                            .where<UserEntity>()
                            .equalTo(UserEntityFields.USER_ID, userId)
                },
                { entity ->
                    entity.devices.map { CryptoMapper.mapToModel(it) }
                }
        )
        return Transformations.map(liveData) {
            it.firstOrNull() ?: emptyList()
        }
    }

    override fun getLiveDeviceList(userIds: List<String>): LiveData<List<CryptoDeviceInfo>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm: Realm ->
                    realm
                            .where<UserEntity>()
                            .`in`(UserEntityFields.USER_ID, userIds.distinct().toTypedArray())
                },
                { entity ->
                    entity.devices.map { CryptoMapper.mapToModel(it) }
                }
        )
        return Transformations.map(liveData) {
            it.flatten()
        }
    }

    override fun getLiveDeviceList(): LiveData<List<CryptoDeviceInfo>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm: Realm ->
                    realm.where<UserEntity>()
                },
                { entity ->
                    entity.devices.map { CryptoMapper.mapToModel(it) }
                }
        )
        return Transformations.map(liveData) {
            it.firstOrNull() ?: emptyList()
        }
    }

    override fun storeRoomAlgorithm(roomId: String, algorithm: String) {
        doRealmTransaction(realmConfiguration) {
            CryptoRoomEntity.getOrCreate(it, roomId).algorithm = algorithm
        }
    }

    override fun getRoomAlgorithm(roomId: String): String? {
        return doRealmQueryAndCopy(realmConfiguration) {
            CryptoRoomEntity.getById(it, roomId)
        }
                ?.algorithm
    }

    override fun shouldEncryptForInvitedMembers(roomId: String): Boolean {
        return doRealmQueryAndCopy(realmConfiguration) {
            CryptoRoomEntity.getById(it, roomId)
        }
                ?.shouldEncryptForInvitedMembers ?: false
    }

    override fun setShouldEncryptForInvitedMembers(roomId: String, shouldEncryptForInvitedMembers: Boolean) {
        doRealmTransaction(realmConfiguration) {
            CryptoRoomEntity.getOrCreate(it, roomId).shouldEncryptForInvitedMembers = shouldEncryptForInvitedMembers
        }
    }

    override fun storeSession(olmSessionWrapper: OlmSessionWrapper, deviceKey: String) {
        var sessionIdentifier: String? = null

        try {
            sessionIdentifier = olmSessionWrapper.olmSession.sessionIdentifier()
        } catch (e: OlmException) {
            Timber.e(e, "## storeSession() : sessionIdentifier failed")
        }

        if (sessionIdentifier != null) {
            val key = OlmSessionEntity.createPrimaryKey(sessionIdentifier, deviceKey)

            // Release memory of previously known session, if it is not the same one
            if (olmSessionsToRelease[key]?.olmSession != olmSessionWrapper.olmSession) {
                olmSessionsToRelease[key]?.olmSession?.releaseSession()
            }

            olmSessionsToRelease[key] = olmSessionWrapper

            doRealmTransaction(realmConfiguration) {
                val realmOlmSession = OlmSessionEntity().apply {
                    primaryKey = key
                    sessionId = sessionIdentifier
                    this.deviceKey = deviceKey
                    putOlmSession(olmSessionWrapper.olmSession)
                    lastReceivedMessageTs = olmSessionWrapper.lastReceivedMessageTs
                }

                it.insertOrUpdate(realmOlmSession)
            }
        }
    }

    override fun getDeviceSession(sessionId: String?, deviceKey: String?): OlmSessionWrapper? {
        if (sessionId == null || deviceKey == null) {
            return null
        }

        val key = OlmSessionEntity.createPrimaryKey(sessionId, deviceKey)

        // If not in cache (or not found), try to read it from realm
        if (olmSessionsToRelease[key] == null) {
            doRealmQueryAndCopy(realmConfiguration) {
                it.where<OlmSessionEntity>()
                        .equalTo(OlmSessionEntityFields.PRIMARY_KEY, key)
                        .findFirst()
            }
                    ?.let {
                        val olmSession = it.getOlmSession()
                        if (olmSession != null && it.sessionId != null) {
                            olmSessionsToRelease[key] = OlmSessionWrapper(olmSession, it.lastReceivedMessageTs)
                        }
                    }
        }

        return olmSessionsToRelease[key]
    }

    override fun getLastUsedSessionId(deviceKey: String): String? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<OlmSessionEntity>()
                    .equalTo(OlmSessionEntityFields.DEVICE_KEY, deviceKey)
                    .sort(OlmSessionEntityFields.LAST_RECEIVED_MESSAGE_TS, Sort.DESCENDING)
                    .findFirst()
        }
                ?.sessionId
    }

    override fun getDeviceSessionIds(deviceKey: String): MutableSet<String> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<OlmSessionEntity>()
                    .equalTo(OlmSessionEntityFields.DEVICE_KEY, deviceKey)
                    .findAll()
        }
                .mapNotNull {
                    it.sessionId
                }
                .toMutableSet()
    }

    override fun storeInboundGroupSessions(sessions: List<OlmInboundGroupSessionWrapper>) {
        if (sessions.isEmpty()) {
            return
        }

        doRealmTransaction(realmConfiguration) { realm ->
            sessions.forEach { session ->
                var sessionIdentifier: String? = null

                try {
                    sessionIdentifier = session.olmInboundGroupSession?.sessionIdentifier()
                } catch (e: OlmException) {
                    Timber.e(e, "## storeInboundGroupSession() : sessionIdentifier failed")
                }

                if (sessionIdentifier != null) {
                    val key = OlmInboundGroupSessionEntity.createPrimaryKey(sessionIdentifier, session.senderKey)

                    // Release memory of previously known session, if it is not the same one
                    if (inboundGroupSessionToRelease[key] != session) {
                        inboundGroupSessionToRelease[key]?.olmInboundGroupSession?.releaseSession()
                    }

                    inboundGroupSessionToRelease[key] = session

                    val realmOlmInboundGroupSession = OlmInboundGroupSessionEntity().apply {
                        primaryKey = key
                        sessionId = sessionIdentifier
                        senderKey = session.senderKey
                        putInboundGroupSession(session)
                    }

                    realm.insertOrUpdate(realmOlmInboundGroupSession)
                }
            }
        }
    }

    override fun getInboundGroupSession(sessionId: String, senderKey: String): OlmInboundGroupSessionWrapper? {
        val key = OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, senderKey)

        // If not in cache (or not found), try to read it from realm
        if (inboundGroupSessionToRelease[key] == null) {
            doRealmQueryAndCopy(realmConfiguration) {
                it.where<OlmInboundGroupSessionEntity>()
                        .equalTo(OlmInboundGroupSessionEntityFields.PRIMARY_KEY, key)
                        .findFirst()
            }
                    ?.getInboundGroupSession()
                    ?.let {
                        inboundGroupSessionToRelease[key] = it
                    }
        }

        return inboundGroupSessionToRelease[key]
    }

    /**
     * Note: the result will be only use to export all the keys and not to use the OlmInboundGroupSessionWrapper,
     * so there is no need to use or update `inboundGroupSessionToRelease` for native memory management
     */
    override fun getInboundGroupSessions(): MutableList<OlmInboundGroupSessionWrapper> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<OlmInboundGroupSessionEntity>()
                    .findAll()
        }
                .mapNotNull {
                    it.getInboundGroupSession()
                }
                .toMutableList()
    }

    override fun removeInboundGroupSession(sessionId: String, senderKey: String) {
        val key = OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, senderKey)

        // Release memory of previously known session
        inboundGroupSessionToRelease[key]?.olmInboundGroupSession?.releaseSession()
        inboundGroupSessionToRelease.remove(key)

        doRealmTransaction(realmConfiguration) {
            it.where<OlmInboundGroupSessionEntity>()
                    .equalTo(OlmInboundGroupSessionEntityFields.PRIMARY_KEY, key)
                    .findAll()
                    .deleteAllFromRealm()
        }
    }

    /* ==========================================================================================
     * Keys backup
     * ========================================================================================== */

    override fun getKeyBackupVersion(): String? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()
        }?.backupVersion
    }

    override fun setKeyBackupVersion(keyBackupVersion: String?) {
        doRealmTransaction(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()?.backupVersion = keyBackupVersion
        }
    }

    override fun getKeysBackupData(): KeysBackupDataEntity? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<KeysBackupDataEntity>().findFirst()
        }
    }

    override fun setKeysBackupData(keysBackupData: KeysBackupDataEntity?) {
        doRealmTransaction(realmConfiguration) {
            if (keysBackupData == null) {
                // Clear the table
                it.where<KeysBackupDataEntity>()
                        .findAll()
                        .deleteAllFromRealm()
            } else {
                // Only one object
                it.copyToRealmOrUpdate(keysBackupData)
            }
        }
    }

    override fun resetBackupMarkers() {
        doRealmTransaction(realmConfiguration) {
            it.where<OlmInboundGroupSessionEntity>()
                    .findAll()
                    .map { inboundGroupSession ->
                        inboundGroupSession.backedUp = false
                    }
        }
    }

    override fun markBackupDoneForInboundGroupSessions(olmInboundGroupSessionWrappers: List<OlmInboundGroupSessionWrapper>) {
        if (olmInboundGroupSessionWrappers.isEmpty()) {
            return
        }

        doRealmTransaction(realmConfiguration) {
            olmInboundGroupSessionWrappers.forEach { olmInboundGroupSessionWrapper ->
                try {
                    val key = OlmInboundGroupSessionEntity.createPrimaryKey(
                            olmInboundGroupSessionWrapper.olmInboundGroupSession?.sessionIdentifier(),
                            olmInboundGroupSessionWrapper.senderKey)

                    it.where<OlmInboundGroupSessionEntity>()
                            .equalTo(OlmInboundGroupSessionEntityFields.PRIMARY_KEY, key)
                            .findFirst()
                            ?.backedUp = true
                } catch (e: OlmException) {
                    Timber.e(e, "OlmException")
                }
            }
        }
    }

    override fun inboundGroupSessionsToBackup(limit: Int): List<OlmInboundGroupSessionWrapper> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<OlmInboundGroupSessionEntity>()
                    .equalTo(OlmInboundGroupSessionEntityFields.BACKED_UP, false)
                    .limit(limit.toLong())
                    .findAll()
        }.mapNotNull { inboundGroupSession ->
            inboundGroupSession.getInboundGroupSession()
        }
    }

    override fun inboundGroupSessionsCount(onlyBackedUp: Boolean): Int {
        return doWithRealm(realmConfiguration) {
            it.where<OlmInboundGroupSessionEntity>()
                    .apply {
                        if (onlyBackedUp) {
                            equalTo(OlmInboundGroupSessionEntityFields.BACKED_UP, true)
                        }
                    }
                    .count()
                    .toInt()
        }
    }

    override fun setGlobalBlacklistUnverifiedDevices(block: Boolean) {
        doRealmTransaction(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()?.globalBlacklistUnverifiedDevices = block
        }
    }

    override fun getGlobalBlacklistUnverifiedDevices(): Boolean {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()
        }?.globalBlacklistUnverifiedDevices
                ?: false
    }

    override fun setRoomsListBlacklistUnverifiedDevices(roomIds: List<String>) {
        doRealmTransaction(realmConfiguration) {
            // Reset all
            it.where<CryptoRoomEntity>()
                    .findAll()
                    .forEach { room ->
                        room.blacklistUnverifiedDevices = false
                    }

            // Enable those in the list
            it.where<CryptoRoomEntity>()
                    .`in`(CryptoRoomEntityFields.ROOM_ID, roomIds.toTypedArray())
                    .findAll()
                    .forEach { room ->
                        room.blacklistUnverifiedDevices = true
                    }
        }
    }

    override fun getRoomsListBlacklistUnverifiedDevices(): MutableList<String> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<CryptoRoomEntity>()
                    .equalTo(CryptoRoomEntityFields.BLACKLIST_UNVERIFIED_DEVICES, true)
                    .findAll()
        }
                .mapNotNull {
                    it.roomId
                }
                .toMutableList()
    }

    override fun getDeviceTrackingStatuses(): MutableMap<String, Int> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<UserEntity>()
                    .findAll()
        }
                .associateBy {
                    it.userId!!
                }
                .mapValues {
                    it.value.deviceTrackingStatus
                }
                .toMutableMap()
    }

    override fun saveDeviceTrackingStatuses(deviceTrackingStatuses: Map<String, Int>) {
        doRealmTransaction(realmConfiguration) {
            deviceTrackingStatuses
                    .map { entry ->
                        UserEntity.getOrCreate(it, entry.key)
                                .deviceTrackingStatus = entry.value
                    }
        }
    }

    override fun getDeviceTrackingStatus(userId: String, defaultValue: Int): Int {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<UserEntity>()
                    .equalTo(UserEntityFields.USER_ID, userId)
                    .findFirst()
        }
                ?.deviceTrackingStatus
                ?: defaultValue
    }

    override fun getOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody): OutgoingRoomKeyRequest? {
        return monarchy.fetchAllCopiedSync { realm ->
            realm.where<OutgoingGossipingRequestEntity>()
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
        }.mapNotNull {
            it.toOutgoingGossipingRequest() as? OutgoingRoomKeyRequest
        }.firstOrNull {
            it.requestBody?.algorithm == requestBody.algorithm
            it.requestBody?.roomId == requestBody.roomId
            it.requestBody?.senderKey == requestBody.senderKey
            it.requestBody?.sessionId == requestBody.sessionId
        }
    }

    override fun getOutgoingSecretRequest(secretName: String): OutgoingSecretRequest? {
        return monarchy.fetchAllCopiedSync { realm ->
            realm.where<OutgoingGossipingRequestEntity>()
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.SECRET.name)
                    .equalTo(OutgoingGossipingRequestEntityFields.REQUESTED_INFO_STR, secretName)
        }.mapNotNull {
            it.toOutgoingGossipingRequest() as? OutgoingSecretRequest
        }.firstOrNull()
    }

    override fun getIncomingRoomKeyRequests(): List<IncomingRoomKeyRequest> {
        return monarchy.fetchAllCopiedSync { realm ->
            realm.where<IncomingGossipingRequestEntity>()
                    .equalTo(IncomingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
        }.mapNotNull {
            it.toIncomingGossipingRequest() as? IncomingRoomKeyRequest
        }
    }

    override fun getGossipingEventsTrail(): List<Event> {
        return monarchy.fetchAllCopiedSync { realm ->
            realm.where<GossipingEventEntity>()
        }.map {
            it.toModel()
        }
    }

    override fun getOrAddOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody, recipients: Map<String, List<String>>): OutgoingRoomKeyRequest? {
        // Insert the request and return the one passed in parameter
        var request: OutgoingRoomKeyRequest? = null
        doRealmTransaction(realmConfiguration) { realm ->

            val existing = realm.where<OutgoingGossipingRequestEntity>()
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
                    .findAll()
                    .mapNotNull {
                        it.toOutgoingGossipingRequest() as? OutgoingRoomKeyRequest
                    }.firstOrNull {
                        it.requestBody?.algorithm == requestBody.algorithm
                                && it.requestBody?.sessionId == requestBody.sessionId
                                && it.requestBody?.senderKey == requestBody.senderKey
                                && it.requestBody?.roomId == requestBody.roomId
                    }

            if (existing == null) {
                request = realm.createObject(OutgoingGossipingRequestEntity::class.java).apply {
                    this.requestId = LocalEcho.createLocalEchoId()
                    this.setRecipients(recipients)
                    this.requestState = OutgoingGossipingRequestState.UNSENT
                    this.type = GossipRequestType.KEY
                    this.requestedInfoStr = requestBody.toJson()
                }.toOutgoingGossipingRequest() as? OutgoingRoomKeyRequest
            } else {
                request = existing
            }
        }
        return request
    }

    override fun getOrAddOutgoingSecretShareRequest(secretName: String, recipients: Map<String, List<String>>): OutgoingSecretRequest? {
        var request: OutgoingSecretRequest? = null

        // Insert the request and return the one passed in parameter
        doRealmTransaction(realmConfiguration) { realm ->
            val existing = realm.where<OutgoingGossipingRequestEntity>()
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.SECRET.name)
                    .equalTo(OutgoingGossipingRequestEntityFields.REQUESTED_INFO_STR, secretName)
                    .findAll()
                    .mapNotNull {
                        it.toOutgoingGossipingRequest() as? OutgoingSecretRequest
                    }.firstOrNull()
            if (existing == null) {
                request = realm.createObject(OutgoingGossipingRequestEntity::class.java).apply {
                    this.type = GossipRequestType.SECRET
                    setRecipients(recipients)
                    this.requestState = OutgoingGossipingRequestState.UNSENT
                    this.requestId = LocalEcho.createLocalEchoId()
                    this.requestedInfoStr = secretName
                }.toOutgoingGossipingRequest() as? OutgoingSecretRequest
            } else {
                request = existing
            }
        }

        return request
    }

    override fun saveGossipingEvent(event: Event) {
        val now = System.currentTimeMillis()
        val ageLocalTs = event.unsignedData?.age?.let { now - it } ?: now
        val entity = GossipingEventEntity(
                type = event.type,
                sender = event.senderId,
                ageLocalTs = ageLocalTs,
                content = ContentMapper.map(event.content)
        ).apply {
            sendState = SendState.SYNCED
            decryptionResultJson = MoshiProvider.providesMoshi().adapter<OlmDecryptionResult>(OlmDecryptionResult::class.java).toJson(event.mxDecryptionResult)
            decryptionErrorCode = event.mCryptoError?.name
        }
        doRealmTransaction(realmConfiguration) { realm ->
            realm.insertOrUpdate(entity)
        }
    }

//    override fun getOutgoingRoomKeyRequestByState(states: Set<ShareRequestState>): OutgoingRoomKeyRequest? {
//        val statesIndex = states.map { it.ordinal }.toTypedArray()
//        return doRealmQueryAndCopy(realmConfiguration) { realm ->
//            realm.where<GossipingEventEntity>()
//                    .equalTo(GossipingEventEntityFields.SENDER, credentials.userId)
//                    .findAll()
//                    .filter {entity ->
//                        states.any { it == entity.requestState}
//                    }
//        }.mapNotNull {
//            ContentMapper.map(it.content)?.toModel<OutgoingSecretRequest>()
//        }
//                ?.toOutgoingRoomKeyRequest()
//    }
//
//    override fun getOutgoingSecretShareRequestByState(states: Set<ShareRequestState>): OutgoingSecretRequest? {
//        val statesIndex = states.map { it.ordinal }.toTypedArray()
//        return doRealmQueryAndCopy(realmConfiguration) {
//            it.where<OutgoingSecretRequestEntity>()
//                    .`in`(OutgoingSecretRequestEntityFields.STATE, statesIndex)
//                    .findFirst()
//        }
//                ?.toOutgoingSecretRequest()
//    }

//    override fun updateOutgoingRoomKeyRequest(request: OutgoingRoomKeyRequest) {
//        doRealmTransaction(realmConfiguration) {
//            val obj = OutgoingRoomKeyRequestEntity().apply {
//                requestId = request.requestId
//                cancellationTxnId = request.cancellationTxnId
//                state = request.state.ordinal
//                putRecipients(request.recipients)
//                putRequestBody(request.requestBody)
//            }
//
//            it.insertOrUpdate(obj)
//        }
//    }

//    override fun deleteOutgoingRoomKeyRequest(transactionId: String) {
//        doRealmTransaction(realmConfiguration) {
//            it.where<OutgoingRoomKeyRequestEntity>()
//                    .equalTo(OutgoingRoomKeyRequestEntityFields.REQUEST_ID, transactionId)
//                    .findFirst()
//                    ?.deleteFromRealm()
//        }
//    }

//    override fun storeIncomingRoomKeyRequest(incomingRoomKeyRequest: IncomingRoomKeyRequest?) {
//        if (incomingRoomKeyRequest == null) {
//            return
//        }
//
//        doRealmTransaction(realmConfiguration) {
//            // Delete any previous store request with the same parameters
//            it.where<IncomingRoomKeyRequestEntity>()
//                    .equalTo(IncomingRoomKeyRequestEntityFields.USER_ID, incomingRoomKeyRequest.userId)
//                    .equalTo(IncomingRoomKeyRequestEntityFields.DEVICE_ID, incomingRoomKeyRequest.deviceId)
//                    .equalTo(IncomingRoomKeyRequestEntityFields.REQUEST_ID, incomingRoomKeyRequest.requestId)
//                    .findAll()
//                    .deleteAllFromRealm()
//
//            // Then store it
//            it.createObject(IncomingRoomKeyRequestEntity::class.java).apply {
//                userId = incomingRoomKeyRequest.userId
//                deviceId = incomingRoomKeyRequest.deviceId
//                requestId = incomingRoomKeyRequest.requestId
//                putRequestBody(incomingRoomKeyRequest.requestBody)
//            }
//        }
//    }

//    override fun deleteIncomingRoomKeyRequest(incomingRoomKeyRequest: IncomingShareRequestCommon) {
//        doRealmTransaction(realmConfiguration) {
//            it.where<GossipingEventEntity>()
//                    .equalTo(GossipingEventEntityFields.TYPE, EventType.ROOM_KEY_REQUEST)
//                    .notEqualTo(GossipingEventEntityFields.SENDER, credentials.userId)
//                    .findAll()
//                    .filter {
//                        ContentMapper.map(it.content).toModel<IncomingRoomKeyRequest>()?.let {
//
//                        }
//                    }
// //                    .equalTo(IncomingRoomKeyRequestEntityFields.USER_ID, incomingRoomKeyRequest.userId)
// //                    .equalTo(IncomingRoomKeyRequestEntityFields.DEVICE_ID, incomingRoomKeyRequest.deviceId)
// //                    .equalTo(IncomingRoomKeyRequestEntityFields.REQUEST_ID, incomingRoomKeyRequest.requestId)
// //                    .findAll()
// //                    .deleteAllFromRealm()
//        }
//    }

    override fun updateGossipingRequestState(request: IncomingShareRequestCommon, state: GossipingRequestState) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<IncomingGossipingRequestEntity>()
                    .equalTo(IncomingGossipingRequestEntityFields.OTHER_USER_ID, request.userId)
                    .equalTo(IncomingGossipingRequestEntityFields.OTHER_DEVICE_ID, request.deviceId)
                    .equalTo(IncomingGossipingRequestEntityFields.REQUEST_ID, request.requestId)
                    .findAll().forEach {
                        it.requestState = state
                    }
        }
    }

    override fun updateOutgoingGossipingRequestState(requestId: String, state: OutgoingGossipingRequestState) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<OutgoingGossipingRequestEntity>()
                    .equalTo(OutgoingGossipingRequestEntityFields.REQUEST_ID, requestId)
                    .findAll().forEach {
                        it.requestState = state
                    }
        }
    }

    override fun getIncomingRoomKeyRequest(userId: String, deviceId: String, requestId: String): IncomingRoomKeyRequest? {
        return doRealmQueryAndCopyList(realmConfiguration) { realm ->
            realm.where<IncomingGossipingRequestEntity>()
                    .equalTo(IncomingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
                    .equalTo(IncomingGossipingRequestEntityFields.OTHER_DEVICE_ID, deviceId)
                    .equalTo(IncomingGossipingRequestEntityFields.OTHER_USER_ID, userId)
                    .findAll()
        }.mapNotNull { entity ->
            entity.toIncomingGossipingRequest() as? IncomingRoomKeyRequest
        }.firstOrNull()
    }

    override fun getPendingIncomingRoomKeyRequests(): List<IncomingRoomKeyRequest> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<IncomingGossipingRequestEntity>()
                    .equalTo(IncomingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
                    .equalTo(IncomingGossipingRequestEntityFields.REQUEST_STATE_STR, GossipingRequestState.PENDING.name)
                    .findAll()
        }
                .map { entity ->
                    IncomingRoomKeyRequest(
                            userId = entity.otherUserId,
                            deviceId = entity.otherDeviceId,
                            requestId = entity.requestId,
                            requestBody = entity.getRequestedKeyInfo(),
                            localCreationTimestamp = entity.localCreationTimestamp
                    )
                }
    }

    override fun getPendingIncomingGossipingRequests(): List<IncomingShareRequestCommon> {
        return doRealmQueryAndCopyList(realmConfiguration) {
            it.where<IncomingGossipingRequestEntity>()
                    .equalTo(IncomingGossipingRequestEntityFields.REQUEST_STATE_STR, GossipingRequestState.PENDING.name)
                    .findAll()
        }
                .mapNotNull { entity ->
                    when (entity.type) {
                        GossipRequestType.KEY    -> {
                            IncomingRoomKeyRequest(
                                    userId = entity.otherUserId,
                                    deviceId = entity.otherDeviceId,
                                    requestId = entity.requestId,
                                    requestBody = entity.getRequestedKeyInfo(),
                                    localCreationTimestamp = entity.localCreationTimestamp
                            )
                        }
                        GossipRequestType.SECRET -> {
                            IncomingSecretShareRequest(
                                    userId = entity.otherUserId,
                                    deviceId = entity.otherDeviceId,
                                    requestId = entity.requestId,
                                    secretName = entity.getRequestedSecretName(),
                                    localCreationTimestamp = entity.localCreationTimestamp
                            )
                        }
                    }
                }
    }

    override fun storeIncomingGossipingRequest(request: IncomingShareRequestCommon, ageLocalTS: Long?) {
        doRealmTransactionAsync(realmConfiguration) { realm ->

            // After a clear cache, we might have a

            realm.createObject(IncomingGossipingRequestEntity::class.java).let {
                it.otherDeviceId = request.deviceId
                it.otherUserId = request.userId
                it.requestId = request.requestId ?: ""
                it.requestState = GossipingRequestState.PENDING
                it.localCreationTimestamp = ageLocalTS ?: System.currentTimeMillis()
                if (request is IncomingSecretShareRequest) {
                    it.type = GossipRequestType.SECRET
                    it.requestedInfoStr = request.secretName
                } else if (request is IncomingRoomKeyRequest) {
                    it.type = GossipRequestType.KEY
                    it.requestedInfoStr = request.requestBody?.toJson()
                }
            }
        }
    }

//    override fun getPendingIncomingSecretShareRequests(): List<IncomingSecretShareRequest> {
//        return doRealmQueryAndCopyList(realmConfiguration) {
//            it.where<GossipingEventEntity>()
//                    .findAll()
//        }.map {
//            it.toIncomingSecretShareRequest()
//        }
//    }

    /* ==========================================================================================
     * Cross Signing
     * ========================================================================================== */
    override fun getMyCrossSigningInfo(): MXCrossSigningInfo? {
        return doRealmQueryAndCopy(realmConfiguration) {
            it.where<CryptoMetadataEntity>().findFirst()
        }?.userId?.let {
            getCrossSigningInfo(it)
        }
    }

    override fun setMyCrossSigningInfo(info: MXCrossSigningInfo?) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.userId?.let { userId ->
                addOrUpdateCrossSigningInfo(realm, userId, info)
            }
        }
    }

    override fun setUserKeysAsTrusted(userId: String, trusted: Boolean) {
        doRealmTransaction(realmConfiguration) { realm ->
            val xInfoEntity = realm.where(CrossSigningInfoEntity::class.java)
                    .equalTo(CrossSigningInfoEntityFields.USER_ID, userId)
                    .findFirst()
            xInfoEntity?.crossSigningKeys?.forEach { info ->
                val level = info.trustLevelEntity
                if (level == null) {
                    val newLevel = realm.createObject(TrustLevelEntity::class.java)
                    newLevel.locallyVerified = trusted
                    newLevel.crossSignedVerified = trusted
                    info.trustLevelEntity = newLevel
                } else {
                    level.locallyVerified = trusted
                    level.crossSignedVerified = trusted
                }
            }
        }
    }

    override fun setDeviceTrust(userId: String, deviceId: String, crossSignedVerified: Boolean, locallyVerified: Boolean) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where(DeviceInfoEntity::class.java)
                    .equalTo(DeviceInfoEntityFields.PRIMARY_KEY, DeviceInfoEntity.createPrimaryKey(userId, deviceId))
                    .findFirst()?.let { deviceInfoEntity ->
                        val trustEntity = deviceInfoEntity.trustLevelEntity
                        if (trustEntity == null) {
                            realm.createObject(TrustLevelEntity::class.java).let {
                                it.locallyVerified = locallyVerified
                                it.crossSignedVerified = crossSignedVerified
                                deviceInfoEntity.trustLevelEntity = it
                            }
                        } else {
                            trustEntity.locallyVerified = locallyVerified
                            trustEntity.crossSignedVerified = crossSignedVerified
                        }
                    }
        }
    }

    override fun clearOtherUserTrust() {
        doRealmTransaction(realmConfiguration) { realm ->
            val xInfoEntities = realm.where(CrossSigningInfoEntity::class.java)
                    .findAll()
            xInfoEntities?.forEach { info ->
                // Need to ignore mine
                if (info.userId != credentials.userId) {
                    info.crossSigningKeys.forEach {
                        it.trustLevelEntity = null
                    }
                }
            }
        }
    }

    override fun updateUsersTrust(check: (String) -> Boolean) {
        doRealmTransaction(realmConfiguration) { realm ->
            val xInfoEntities = realm.where(CrossSigningInfoEntity::class.java)
                    .findAll()
            xInfoEntities?.forEach { xInfoEntity ->
                // Need to ignore mine
                if (xInfoEntity.userId == credentials.userId) return@forEach
                val mapped = mapCrossSigningInfoEntity(xInfoEntity)
                val currentTrust = mapped.isTrusted()
                val newTrust = check(mapped.userId)
                if (currentTrust != newTrust) {
                    xInfoEntity.crossSigningKeys.forEach { info ->
                        val level = info.trustLevelEntity
                        if (level == null) {
                            val newLevel = realm.createObject(TrustLevelEntity::class.java)
                            newLevel.locallyVerified = newTrust
                            newLevel.crossSignedVerified = newTrust
                            info.trustLevelEntity = newLevel
                        } else {
                            level.locallyVerified = newTrust
                            level.crossSignedVerified = newTrust
                        }
                    }
                }
            }
        }
    }

    override fun getOutgoingRoomKeyRequests(): List<OutgoingRoomKeyRequest> {
        return monarchy.fetchAllMappedSync({ realm ->
            realm
                    .where(OutgoingGossipingRequestEntity::class.java)
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.KEY.name)
        }, { entity ->
            entity.toOutgoingGossipingRequest() as? OutgoingRoomKeyRequest
        })
                .filterNotNull()
    }

    override fun getOutgoingSecretKeyRequests(): List<OutgoingSecretRequest> {
        return monarchy.fetchAllMappedSync({ realm ->
            realm
                    .where(OutgoingGossipingRequestEntity::class.java)
                    .equalTo(OutgoingGossipingRequestEntityFields.TYPE_STR, GossipRequestType.SECRET.name)
        }, { entity ->
            entity.toOutgoingGossipingRequest() as? OutgoingSecretRequest
        })
                .filterNotNull()
    }

    override fun getCrossSigningInfo(userId: String): MXCrossSigningInfo? {
        return doRealmQueryAndCopy(realmConfiguration) { realm ->
            realm.where(CrossSigningInfoEntity::class.java)
                    .equalTo(CrossSigningInfoEntityFields.USER_ID, userId)
                    .findFirst()
        }?.let { xsignInfo ->
            mapCrossSigningInfoEntity(xsignInfo)
        }
    }

    private fun mapCrossSigningInfoEntity(xsignInfo: CrossSigningInfoEntity): MXCrossSigningInfo {
        return MXCrossSigningInfo(
                userId = xsignInfo.userId ?: "",
                crossSigningKeys = xsignInfo.crossSigningKeys.mapNotNull {
                    val pubKey = it.publicKeyBase64 ?: return@mapNotNull null
                    CryptoCrossSigningKey(
                            userId = xsignInfo.userId ?: "",
                            keys = mapOf("ed25519:$pubKey" to pubKey),
                            usages = it.usages.map { it },
                            signatures = it.getSignatures(),
                            trustLevel = it.trustLevelEntity?.let {
                                DeviceTrustLevel(
                                        crossSigningVerified = it.crossSignedVerified ?: false,
                                        locallyVerified = it.locallyVerified ?: false
                                )
                            }

                    )
                }
        )
    }

    override fun getLiveCrossSigningInfo(userId: String): LiveData<Optional<MXCrossSigningInfo>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm: Realm ->
                    realm.where<CrossSigningInfoEntity>()
                            .equalTo(UserEntityFields.USER_ID, userId)
                },
                { entity ->
                    MXCrossSigningInfo(
                            userId = userId,
                            crossSigningKeys = entity.crossSigningKeys.mapNotNull {
                                val pubKey = it.publicKeyBase64 ?: return@mapNotNull null
                                CryptoCrossSigningKey(
                                        userId = userId,
                                        keys = mapOf("ed25519:$pubKey" to pubKey),
                                        usages = it.usages.map { it },
                                        signatures = it.getSignatures(),
                                        trustLevel = it.trustLevelEntity?.let {
                                            DeviceTrustLevel(
                                                    crossSigningVerified = it.crossSignedVerified ?: false,
                                                    locallyVerified = it.locallyVerified ?: false
                                            )
                                        }
                                )
                            }
                    )
                }
        )
        return Transformations.map(liveData) {
            it.firstOrNull().toOptional()
        }
    }

    override fun setCrossSigningInfo(userId: String, info: MXCrossSigningInfo?) {
        doRealmTransaction(realmConfiguration) { realm ->
            addOrUpdateCrossSigningInfo(realm, userId, info)
        }
    }

    override fun markMyMasterKeyAsLocallyTrusted(trusted: Boolean) {
        doRealmTransaction(realmConfiguration) { realm ->
            realm.where<CryptoMetadataEntity>().findFirst()?.userId?.let { myUserId ->
                CrossSigningInfoEntity.get(realm, myUserId)?.getMasterKey()?.let { xInfoEntity ->
                    val level = xInfoEntity.trustLevelEntity
                    if (level == null) {
                        val newLevel = realm.createObject(TrustLevelEntity::class.java)
                        newLevel.locallyVerified = trusted
                        xInfoEntity.trustLevelEntity = newLevel
                    } else {
                        level.locallyVerified = trusted
                    }
                }
            }
        }
    }

    private fun addOrUpdateCrossSigningInfo(realm: Realm, userId: String, info: MXCrossSigningInfo?): CrossSigningInfoEntity? {
        var existing = CrossSigningInfoEntity.get(realm, userId)
        if (info == null) {
            // Delete known if needed
            existing?.deleteFromRealm()
            // TODO notify, we might need to untrust things?
        } else {
            // Just override existing, caller should check and untrust id needed
            existing = CrossSigningInfoEntity.getOrCreate(realm, userId)
            // existing.crossSigningKeys.forEach { it.deleteFromRealm() }
            val xkeys = RealmList<KeyInfoEntity>()
            info.crossSigningKeys.forEach { cryptoCrossSigningKey ->
                xkeys.add(
                        realm.createObject(KeyInfoEntity::class.java).also { keyInfoEntity ->
                            keyInfoEntity.publicKeyBase64 = cryptoCrossSigningKey.unpaddedBase64PublicKey
                            keyInfoEntity.usages = cryptoCrossSigningKey.usages?.let { RealmList(*it.toTypedArray()) }
                                    ?: RealmList()
                            keyInfoEntity.putSignatures(cryptoCrossSigningKey.signatures)
                            // TODO how to handle better, check if same keys?
                            // reset trust
                            keyInfoEntity.trustLevelEntity = null
                        }
                )
            }
            existing.crossSigningKeys = xkeys
        }
        return existing
    }
}

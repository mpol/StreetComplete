package de.westnordost.streetcomplete.data.user

import android.util.Log
import de.westnordost.osmapi.OsmConnection
import de.westnordost.osmapi.user.Permission
import de.westnordost.osmapi.user.PermissionsDao
import de.westnordost.osmapi.user.UserDao
import de.westnordost.streetcomplete.data.OsmModule
import de.westnordost.streetcomplete.data.user.achievements.*
import de.westnordost.streetcomplete.ktx.saveToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import oauth.signpost.OAuthConsumer
import java.io.File
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton class UserController @Inject constructor(
        private val userDao: UserDao,
        private val oAuthStore: OAuthStore,
        private val userStore: UserStore,
        private val achievementGiver: AchievementGiver,
        private val userAchievementsDao: UserAchievementsDao,
        private val userLinksDao: UserLinksDao,
        @Named("Achievements") achievements: List<Achievement>,
        @Named("Links") links: List<Link>,
        private val avatarCacheDir: File,
        private val statisticsDownloader: StatisticsDownloader,
        private val statisticsDao: QuestStatisticsDao,
        private val osmConnection: OsmConnection
) {
    private val listeners: MutableList<UserLoginStatusListener> = CopyOnWriteArrayList()

    private val achievementsById = achievements.associateBy { it.id }
    private val linksById = links.associateBy { it.id }

    private val lastDateActiveDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val isLoggedIn: Boolean get() = oAuthStore.isAuthorized

    val userId: Long get() = userStore.userId
    val userName: String? get() = userStore.userName

    suspend fun logIn(consumer: OAuthConsumer) {
        withContext(Dispatchers.IO) {
            require(hasRequiredPermissions(consumer)) { "The access does not have the required permissions" }
            oAuthStore.oAuthConsumer = consumer
            osmConnection.oAuth = consumer
            updateUser()
        }
        onLoggedIn()
    }

    fun logOut() {
        userStore.clear()
        oAuthStore.oAuthConsumer = null
        osmConnection.oAuth = null
        statisticsDao.clear()
        userAchievementsDao.clear()
        userLinksDao.clear()
        onLoggedOut()
    }

    fun getAchievements(): List<Pair<Achievement, Int>> {
        return userAchievementsDao.getAll().mapNotNull {
            val achievement = achievementsById[it.key]
            if (achievement != null) achievement to it.value else null
        }
    }

    fun getLinks(): List<Link> {
        return userLinksDao.getAll().mapNotNull { linksById[it] }
    }

    suspend fun updateUser() {
        withContext(Dispatchers.IO) {
            val userDetails = userDao.getMine()
            userStore.setDetails(userDetails)
            downloadAvatar(userDetails.profileImageUrl, userDetails.id)
            syncStatistics(userDetails.id)
        }
    }

    private fun syncStatistics(userId: Long) {
        try {
            val statistics = statisticsDownloader.download(userId)
            if (statistics != null) {
                // TODO maybe only replace if the date is newer than mine...? (Or if there are more?)
                statisticsDao.replaceAll(statistics.amounts)
                userStore.daysActive = statistics.daysActive
                userStore.lastDateActive = lastDateActiveDateFormat.parse(statistics.lastDateActive)
                achievementGiver.updateAchievements()
                achievementGiver.updateAchievementLinks()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to download statistics", e)
        }
    }

    fun addListener(listener: UserLoginStatusListener) {
        listeners.add(listener)
    }
    fun removeListener(listener: UserLoginStatusListener) {
        listeners.remove(listener)
    }

    suspend fun hasRequiredPermissions(consumer: OAuthConsumer): Boolean {
        return withContext(Dispatchers.IO) {
            val permissionDao = PermissionsDao(OsmModule.osmConnection(consumer))
            permissionDao.get().containsAll(REQUIRED_OSM_PERMISSIONS)
        }
    }

    private fun downloadAvatar(avatarUrl: String, userId: Long) {
        try {
            val avatarFile = File(avatarCacheDir, "$userId")
            URL(avatarUrl).saveToFile(avatarFile)
        } catch (e: IOException) {
            Log.w(TAG, "Unable to download avatar user $userId")
        }
    }

    private fun onLoggedIn() {
        for (listener in listeners) {
            listener.onLoggedIn()
        }
    }

    private fun onLoggedOut() {
        for (listener in listeners) {
            listener.onLoggedOut()
        }
    }

    companion object {
        private const val TAG = "UserController"

        private val REQUIRED_OSM_PERMISSIONS = listOf(
                Permission.READ_PREFERENCES_AND_USER_DETAILS,
                Permission.MODIFY_MAP,
                Permission.WRITE_NOTES
        )
    }
}

interface UserLoginStatusListener {
    fun onLoggedIn()
    fun onLoggedOut()
}
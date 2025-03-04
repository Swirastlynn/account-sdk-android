/*
 * Copyright (c) 2018 Schibsted Products & Technology AS. Licensed under the terms of the MIT license. See LICENSE in the project root.
 */

package com.schibsted.account.session

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import android.support.annotation.WorkerThread
import com.schibsted.account.AccountService
import com.schibsted.account.ClientConfiguration
import com.schibsted.account.Events
import com.schibsted.account.common.util.Logger
import com.schibsted.account.engine.integration.ResultCallback
import com.schibsted.account.model.NoValue
import com.schibsted.account.model.UserId
import com.schibsted.account.model.UserToken
import com.schibsted.account.model.error.ClientError
import com.schibsted.account.network.AuthInterceptor
import com.schibsted.account.network.InfoInterceptor
import com.schibsted.account.network.NetworkCallback
import com.schibsted.account.network.OIDCScope
import com.schibsted.account.network.ServiceHolder
import com.schibsted.account.network.response.TokenResponse
import com.schibsted.account.network.service.user.UserService
import com.schibsted.account.persistence.UserPersistence
import okhttp3.OkHttpClient

/**
 * Represents a user and the actions a user can take. Actions are grouped under _auth_, _agreements_ and _profile_,
 */
class User(token: UserToken, val isPersistable: Boolean) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readParcelable<TokenResponse>(TokenResponse::class.java.classLoader),
            parcel.readInt() != 0)

    @Volatile
    internal var token: UserToken? = token
        private set

    val userId: UserId = UserId.fromTokenResponse(token)

    internal var authClient = ServiceHolder.defaultClient.newBuilder()
            .addInterceptor(AuthInterceptor(this, listOf(ClientConfiguration.get().environment))).build()
        private set

    internal var userService = UserService(ClientConfiguration.get().environment, authClient)
        private set

    val auth = Auth(this)

    val agreements = Agreements(this)

    val profile = Profile(this)

    internal val device = Device(AccountService.packageName, AccountService.packageVersion, AccountService.androidId, this)

    fun isActive(): Boolean = token != null

    /**
     * Destroys the current session and removes it's access tokens from Schibsted account. Attempting to use this
     * session afterwards will cause errors
     * @param callback A callback with the result of the logout
     */
    fun logout(callback: ResultCallback<NoValue>?) {
        val token = this.token
        if (token != null) {
            AccountService.localBroadcastManager?.sendBroadcast(Intent(Events.ACTION_USER_LOGOUT).putExtra(Events.EXTRA_USER_ID, userId))
        } else {
            callback?.onError(ClientError(ClientError.ErrorType.INVALID_STATE, "User already logged out"))
        }
    }

    /**
     * Bind this session to an [OkHttpClient]. This will add an interceptor and override any
     * authenticators already defined. Any requests not matching the host will be denied.
     * @param builder An instance of the [OkHttpClient.Builder] to use
     * @param urls A list of urls the [OkHttpClient] will be used for. This will match sub-paths as well
     * @return An [OkHttpClient.Builder] to which the authenticator and interceptor are attached
     */
    @Suppress("MemberVisibilityCanBePrivate", "Unused")
    fun bind(builder: OkHttpClient.Builder, urls: List<String>): OkHttpClient.Builder {
        return bind(builder, urls, false, false)
    }

    /**
     * Bind this session to an [OkHttpClient]. This will add an interceptor and override any
     * authenticators already defined. Any requests not matching the host will be denied.
     * @param builder An instance of the [OkHttpClient.Builder] to use
     * @param urls A list of urls the [OkHttpClient] will be used for. This will match sub-paths as well
     * @param allowNonHttps By default, non-HTTPS requests are denied. Setting this to true will override this.
     * This is not recommended and is done at your own risk.
     * @param allowNonWhitelistedDomains By default, requests to non-whitelisted domains is not allowed. Set this
     * to true to override that.
     * @return An [OkHttpClient.Builder] to which the authenticator and interceptor are attached
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun bind(builder: OkHttpClient.Builder, urls: List<String>, allowNonHttps: Boolean, allowNonWhitelistedDomains: Boolean): OkHttpClient.Builder {
        builder.interceptors().removeAll { it is AuthInterceptor || it is InfoInterceptor }.takeIf { it }?.let {
            Logger.warn("The provided builder had previous sessions bound, these are now removed.")
        }

        return builder
                .addInterceptor(AuthInterceptor(this, urls, allowNonHttps, allowNonWhitelistedDomains))
                .addInterceptor(InfoInterceptor())
    }

    @WorkerThread
    internal fun refreshToken(): Boolean {
        val token = this.token
        if (token == null) {
            Logger.warn("Attempting to refresh token, but user is logged out")
            return false
        }

        val refreshToken = token.refreshToken
        if (refreshToken == null || refreshToken.isBlank()) {
            this.token = null
            Logger.warn("Attempting to refresh token, but the refresh token is empty.")
            return false
        }

        Logger.verbose("Refreshing user token")
        val resp = ServiceHolder.oAuthService.refreshToken(ClientConfiguration.get().clientId,
                ClientConfiguration.get().clientSecret, refreshToken).execute()

        return if (resp.isSuccessful) {
            this.token = requireNotNull(resp.body(), { "Unable to parse token from successful response" })
            Logger.verbose("Refreshing user token was successful")
            AccountService.localBroadcastManager?.sendBroadcast(Intent(Events.ACTION_USER_TOKEN_REFRESH).putExtra(Events.EXTRA_USER, this))
            true
        } else {
            Logger.verbose("User token refreshing failed")
            if (listOf(400, 401, 403).contains(resp.code())) {
                Logger.verbose("Logging out user")
                this@User.token = null

                AccountService.localBroadcastManager?.sendBroadcast(Intent(Events.ACTION_USER_LOGOUT).putExtra(Events.EXTRA_USER_ID, userId))
            }
            false
        }
    }

    /**
     * Manually persist a user session so that it can be resumed at a later point.
     */
    fun persist(context: Context) {
        UserPersistence(context).persist(this)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(token, flags)
        parcel.writeInt(if (this.isPersistable) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<User> {
            override fun createFromParcel(parcel: Parcel): User = User(parcel)

            override fun newArray(size: Int): Array<User?> = arrayOfNulls(size)
        }

        /**
         * @param code The session code to create the user from
         * @param redirectUri The redirect URI. Must be found in self service
         * @param isPersistable If the user can be persisted or not. The user's wishes must be respected to be GDPR compliant
         * @param callback The callback to which we provide the User
         */
        @JvmStatic
        fun fromSessionCode(code: String, redirectUri: String, isPersistable: Boolean, callback: ResultCallback<User>, @OIDCScope scopes: Array<String>?) {
            val conf = ClientConfiguration.get()
            ServiceHolder.oAuthService.tokenFromAuthCode(conf.clientId, conf.clientSecret, code, redirectUri, scopes)
                    .enqueue(NetworkCallback.lambda("Resuming session from session code",
                            { callback.onError(it.toClientError()) },
                            { token ->
                                val user = User(token, isPersistable)
                                callback.onSuccess(user)
                                AccountService.localBroadcastManager?.sendBroadcast(Intent(Events.ACTION_USER_LOGIN).putExtra(Events.EXTRA_USER, user))
                            }
                    ))
        }

        /**
         * Resumes the last active user's session. This verifies that the user has accepted terms
         * and that all required fields are provided. If this fails, onError will be called
         * @param callback The callback for which to return the result
         */
        @JvmStatic
        fun resumeLastSession(appContext: Context, callback: ResultCallback<User>) {
            UserPersistence(appContext).resumeLast(callback)
        }

        /**
         * Resumes the last active user's session. This verifies that the user has accepted terms
         * and that all required fields are provided. If this fails, onError will be called. If no
         * session is found for the user id, onError will be called.
         * @param userId The ID of the user to resume a session for
         * @param callback The callback for which to return the result
         */
        @JvmStatic
        fun resumeSession(appContext: Context, userId: String, callback: ResultCallback<User>) {
            UserPersistence(appContext).resume(userId, callback)
        }

        /**
         * Removes the last user's session, causing it not be be able to be resumed. If your purpose is
         * to to log out the user, call [User.logout] instead.
         */
        @JvmStatic
        fun removeLastSession(appContext: Context) {
            UserPersistence(appContext).removeLast()
        }

        /**
         * Removes a user's session, causing it not be be able to be resumed. If your purpose is
         * to to log out the user, call [User.logout] instead.
         */
        @JvmStatic
        fun removeSession(appContext: Context, userId: String) {
            UserPersistence(appContext).remove(userId)
        }

        /**
         * Clears all stored user sessions
         */
        @JvmStatic
        fun removeAllSession(appContext: Context) {
            UserPersistence(appContext).removeAll()
        }
    }
}

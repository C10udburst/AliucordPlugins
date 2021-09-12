/*
 * Ven's Aliucord Plugins
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
*/

package dev.vendicated.aliucordplugins.spotifylistenalong

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentManager
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PinePatchFn
import com.aliucord.utils.RxUtils.subscribe
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.presence.ActivityUtilsKt
import com.discord.utilities.streams.StreamContext
import com.discord.widgets.user.presence.ModelRichPresence
import com.discord.widgets.user.presence.ViewHolderMusicRichPresence
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.R
import rx.Subscriber
import rx.Subscription

val logger = Logger("SpotifyListenAlong")

@AliucordPlugin
@SuppressLint("SetTextI18n")
class SpotifyListenAlong : Plugin() {
    override fun start(_ctx: Context) {
        val id = View.generateViewId()
        patcher.patch(
            ViewHolderMusicRichPresence::class.java.getDeclaredMethod("configureUi",
                FragmentManager::class.java,
                StreamContext::class.java,
                Boolean::class.javaPrimitiveType,
                User::class.java,
                Context::class.java,
                ModelRichPresence::class.java,
                Boolean::class.javaPrimitiveType
            ), PinePatchFn { cf ->
                val holder = cf.thisObject as ViewHolderMusicRichPresence
                val primaryBtn = holder.richPresencePrimaryButton
                val layout = primaryBtn.parent as ConstraintLayout
                if (layout.findViewById<View>(id) != null) return@PinePatchFn
                val ctx = layout.context

                val userId = (cf.args[3] as User).id
                MaterialButton(
                    ContextThemeWrapper(ctx, R.h.UserProfile_PresenceSection_MaterialButton), null, 0
                ).run {
                    this.id = id
                    layoutParams = ConstraintLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topToBottom = primaryBtn.id
                    }

                    configureButton(this, userId)
                    (primaryBtn.parent as ViewGroup).addView(this)
                }
            })
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()

        stopListening()
    }

    private fun configureButton(btn: MaterialButton, userId: Long) {
        with (btn) {
            when (userId) {
                hostId -> {
                    text = "Stop listening along"
                    setOnClickListener {
                        stopListening()
                        configureButton(btn, userId)
                    }
                }
                StoreStream.getUsers().me.id -> {
                    text = "Listen along with someone else, dummy..."
                    isEnabled = false
                }
                else -> {
                    text = "Listen Along"
                    setOnClickListener {
                        listenAlong(userId)
                        configureButton(btn, userId)
                    }
                }
            }
        }
    }

    private fun stopListening() {
        subscription?.let {
            it.unsubscribe()
            SpotifyApi.pause()
        }
        subscription = null
        currentSong = null
        hostId = null
    }

    private var currentSong: String? = null
    private var hostId: Long? = null
    private var subscription: Subscription? = null

    private fun listenAlong(userId: Long) {
        Utils.showToast(Utils.appContext, "Listening along...")
        subscription?.unsubscribe()

        val obs = StoreStream.getPresences().observePresenceForUser(userId)
        hostId = userId
        subscription = obs.subscribe(object : Subscriber<Presence>() {
            override fun onCompleted() {
                Utils.showToast(Utils.appContext, "The listening party has ended!")
            }

            override fun onError(th: Throwable) {
                logger.error("Error while listening along to $userId", th)
            }

            override fun onNext(p: Presence?) {
                if (p == null) return
                p.activities.forEach {
                    if (ActivityUtilsKt.isSpotifyActivity(it)) {
                        val songId = it.n()

                        val timestamps = it.o()
                        val start = timestamps.c()
                        val offset = (System.currentTimeMillis() - start).toInt()

                        if (songId == currentSong)
                            SpotifyApi.seek(offset)
                        else
                            SpotifyApi.playSong(songId, offset)

                        currentSong = songId
                    }
                }
            }
        })
    }
}

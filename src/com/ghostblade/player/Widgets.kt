package com.ghostblade.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.File

/** Music home-screen widgets (spec §4.6 #106): single-row,
 *  double-row, square big and square small — MX's four music
 *  widget shapes.
 *
 *  WIRING (v0.4 Stage pass — updated honestly): the foreground
 *  PlaybackService (PlayerActivity.kt, declared in the manifest)
 *  now EXISTS and exposes ACTION_TOGGLE / ACTION_NEXT /
 *  ACTION_PREV service intents routed to the live player's
 *  transport. So:
 *  - show the current track (the last track started from the
 *    Music surface, recorded in [Store] by MusicActivity);
 *  - tapping the widget body opens the Music surface;
 *  - tapping ▶ / ⏮ / ⏭ sends the matching PlaybackService action
 *    when the service is declared — real play/pause/next/prev of
 *    the RUNNING player (the service stopSelf()s harmlessly when
 *    no player is alive);
 *  - when the service is NOT declared, the buttons fall back to
 *    the original behaviour: start the player on the stored music
 *    queue at the current / previous / next index.
 *  PARTIAL: a service PendingIntent uses startService semantics;
 *  if the service is not already running, Android's background
 *  start limits can drop the tap instead of cold-starting
 *  playback — cold-start stays on the fallback path above. */
abstract class MusicWidgetBase : AppWidgetProvider() {

    abstract val layoutId: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager,
                          ids: IntArray) {
        for (id in ids) {
            manager.updateAppWidget(id, MusicWidgets.views(context, layoutId))
        }
    }
}

class MusicWidgetSingleRow : MusicWidgetBase() {
    override val layoutId: Int = R.layout.widget_music_single_row
}

class MusicWidgetDoubleRow : MusicWidgetBase() {
    override val layoutId: Int = R.layout.widget_music_double_row
}

class MusicWidgetSquareBig : MusicWidgetBase() {
    override val layoutId: Int = R.layout.widget_music_square_big
}

class MusicWidgetSquareSmall : MusicWidgetBase() {
    override val layoutId: Int = R.layout.widget_music_square_small
}

object MusicWidgets {

    /** Refreshes every placed music widget (called by the Music
     *  surface whenever a track is started). */
    fun refresh(ctx: Context) {
        val manager = AppWidgetManager.getInstance(ctx) ?: return
        update(ctx, manager, MusicWidgetSingleRow::class.java,
            R.layout.widget_music_single_row)
        update(ctx, manager, MusicWidgetDoubleRow::class.java,
            R.layout.widget_music_double_row)
        update(ctx, manager, MusicWidgetSquareBig::class.java,
            R.layout.widget_music_square_big)
        update(ctx, manager, MusicWidgetSquareSmall::class.java,
            R.layout.widget_music_square_small)
    }

    private fun update(ctx: Context, manager: AppWidgetManager,
                       cls: Class<*>, layoutId: Int) {
        val ids = manager.getAppWidgetIds(ComponentName(ctx, cls))
        for (id in ids) manager.updateAppWidget(id, views(ctx, layoutId))
    }

    /** Builds the RemoteViews for one widget instance. All four
     *  layouts share the same view ids (widget_body / widget_title
     *  / widget_artist / widget_prev / widget_play / widget_next). */
    fun views(ctx: Context, layoutId: Int): RemoteViews {
        Store.init(ctx)
        val title = Store.stringSetting(Settings.LAST_MUSIC_TITLE)
            .ifBlank {
                Store.lastPlayedPath().substringAfterLast('/')
                    .substringBeforeLast('.')
            }
            .ifBlank { "Music" }
        val artist = Store.stringSetting(Settings.LAST_MUSIC_ARTIST)
            .ifBlank { "Shadow Player" }
        val views = RemoteViews(ctx.packageName, layoutId)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        val base = layoutId and 0xFFFF
        views.setOnClickPendingIntent(R.id.widget_body,
            pending(ctx, base * 10 + 0, Intent(ctx, MusicActivity::class.java)))
        if (PlaybackService.isDeclared(ctx)) {
            // Real transport of the running player via the service.
            views.setOnClickPendingIntent(R.id.widget_play,
                servicePending(ctx, base * 10 + 1, PlaybackService.ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_prev,
                servicePending(ctx, base * 10 + 2, PlaybackService.ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widget_next,
                servicePending(ctx, base * 10 + 3, PlaybackService.ACTION_NEXT))
        } else {
            views.setOnClickPendingIntent(R.id.widget_play,
                pending(ctx, base * 10 + 1, playIntent(ctx, 0)))
            views.setOnClickPendingIntent(R.id.widget_prev,
                pending(ctx, base * 10 + 2, playIntent(ctx, -1)))
            views.setOnClickPendingIntent(R.id.widget_next,
                pending(ctx, base * 10 + 3, playIntent(ctx, +1)))
        }
        return views
    }

    private fun servicePending(ctx: Context, requestCode: Int,
                               action: String): PendingIntent {
        val intent = Intent(ctx, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Player intent for the stored music queue at the current
     *  index shifted by [delta] (wrapping); falls back to opening
     *  the Music surface when no queue has been recorded yet. */
    private fun playIntent(ctx: Context, delta: Int): Intent {
        val queue = Store.stringListSetting(Settings.LAST_MUSIC_QUEUE)
        if (queue.isEmpty()) return Intent(ctx, MusicActivity::class.java)
        val current = Store.stringSetting(Settings.LAST_MUSIC_PATH)
        val from = queue.indexOf(current).coerceAtLeast(0)
        val idx = Library.wrapIndex(from, delta, queue.size)
        val path = queue[idx]
        val title = if (delta == 0 &&
            Store.stringSetting(Settings.LAST_MUSIC_TITLE).isNotEmpty()) {
            Store.stringSetting(Settings.LAST_MUSIC_TITLE)
        } else {
            File(path).name
        }
        return Intent(ctx, PlayerActivity::class.java).apply {
            putExtra("path", path)
            putExtra("title", title)
            putStringArrayListExtra("playlist", ArrayList(queue))
            putExtra("index", idx)
        }
    }

    private fun pending(ctx: Context, requestCode: Int, intent: Intent): PendingIntent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

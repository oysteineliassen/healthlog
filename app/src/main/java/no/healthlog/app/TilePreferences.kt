package no.healthlog.app

import android.content.Context

private const val PREFS_NAME = "healthlog_prefs"
private const val KEY_METRIC_TILE_ORDER = "metric_tile_order"
private const val KEY_TREND_CARD_ORDER = "trend_card_order"
private const val KEY_TODAY_CARD_ORDER = "today_card_order"
private const val KEY_TODAY_HIDDEN_CARDS = "today_hidden_cards"

object TilePreferences {

    fun getMetricTileOrder(context: Context): List<String>? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_METRIC_TILE_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }

    fun setMetricTileOrder(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_METRIC_TILE_ORDER, order.joinToString(","))
            .apply()
    }

    fun getTrendCardOrder(context: Context): List<String>? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREND_CARD_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }

    fun setTrendCardOrder(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREND_CARD_ORDER, order.joinToString(","))
            .apply()
    }

    fun getTodayCardOrder(context: Context): List<String>? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TODAY_CARD_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }

    fun getHiddenTodayCards(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TODAY_HIDDEN_CARDS, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun setTodayCards(context: Context, order: List<String>, hidden: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TODAY_CARD_ORDER, order.joinToString(","))
            .putString(KEY_TODAY_HIDDEN_CARDS, hidden.joinToString(","))
            .apply()
    }
}

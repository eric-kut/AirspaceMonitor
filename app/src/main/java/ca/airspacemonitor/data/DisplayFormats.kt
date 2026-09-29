package ca.airspacemonitor.data

import ca.airspacemonitor.domain.Units
import java.util.Locale

/** Display formatting for stored feet/km/knot values in the global unit settings. */
object DisplayFormats {

    private const val FEET_PER_MINUTE_PER_METER_PER_SECOND = 60.0 / Units.METERS_PER_FOOT

    fun altitudeSuffix(unit: AltitudeUnit): String = when (unit) {
        AltitudeUnit.FT -> "ft"
        AltitudeUnit.M -> "m"
    }

    fun distanceSuffix(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> "km"
        DistanceUnit.NM -> "nm"
    }

    fun formatAltitude(ft: Double, unit: AltitudeUnit): String = when (unit) {
        AltitudeUnit.FT ->
            String.format(Locale.US, "%,d %s", Math.round(ft).toLong(), altitudeSuffix(unit))
        AltitudeUnit.M ->
            String.format(Locale.US, "%,d %s", Math.round(Units.feetToMeters(ft)).toLong(), altitudeSuffix(unit))
    }

    fun formatDistance(km: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> String.format(Locale.US, "%.1f %s", km, distanceSuffix(unit))
        DistanceUnit.NM -> String.format(Locale.US, "%.1f %s", Units.kmToNm(km), distanceSuffix(unit))
    }

    fun formatSpeed(knots: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> String.format(Locale.US, "%.0f km/h", Units.knotsToKmh(knots))
        DistanceUnit.NM -> String.format(Locale.US, "%.0f kt", knots)
    }

    /**
     * Vertical rate text ("300 fpm" / "1.5 m/s") without the climb/descent word;
     * null when there is no vertical rate.
     */
    fun formatVerticalRate(fpm: Double, unit: AltitudeUnit): String = when (unit) {
        AltitudeUnit.FT -> String.format(Locale.US, "%.0f fpm", fpm)
        AltitudeUnit.M ->
            String.format(Locale.US, "%.1f m/s", fpm / FEET_PER_MINUTE_PER_METER_PER_SECOND)
    }

    /** Rates below this are spoken as "level". */
    private const val LEVEL_THRESHOLD_FPM = 100.0

    /** "level", "climbing at 300 fpm", "descending at 1.5 m/s" — written form for notifications/dialogs. */
    fun verticalRatePhrase(fpm: Double, unit: AltitudeUnit): String = when {
        kotlin.math.abs(fpm) < LEVEL_THRESHOLD_FPM -> "level"
        fpm > 0 -> "climbing at ${formatVerticalRate(fpm, unit)}"
        else -> "descending at ${formatVerticalRate(-fpm, unit)}"
    }

    // ---- Spoken (TTS) forms: full unit words and no grouping commas, so the
    // engine doesn't read "km/h" as "k m slash h" or "1,200" as "1 comma 200".

    fun spokenAltitude(ft: Double, unit: AltitudeUnit): String {
        val value = Math.round(if (unit == AltitudeUnit.M) Units.feetToMeters(ft) else ft).toLong()
        val word = when (unit) {
            AltitudeUnit.FT -> if (value == 1L) "foot" else "feet"
            AltitudeUnit.M -> if (value == 1L) "meter" else "meters"
        }
        return "$value $word"
    }

    fun spokenDistance(km: Double, unit: DistanceUnit): String {
        val value = if (unit == DistanceUnit.KM) km else Units.kmToNm(km)
        val word = when (unit) {
            DistanceUnit.KM -> if (roundsToOne(value)) "kilometer" else "kilometers"
            DistanceUnit.NM -> if (roundsToOne(value)) "nautical mile" else "nautical miles"
        }
        return String.format(Locale.US, "%.1f $word", value)
    }

    fun spokenSpeed(knots: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> String.format(Locale.US, "%.0f kilometers per hour", Units.knotsToKmh(knots))
        DistanceUnit.NM -> String.format(Locale.US, "%.0f knots", knots)
    }

    /** "level", "climbing at 300 feet per minute", "descending at 1.5 meters per second" — spoken form for TTS. */
    fun spokenVerticalRatePhrase(fpm: Double, unit: AltitudeUnit): String = when {
        kotlin.math.abs(fpm) < LEVEL_THRESHOLD_FPM -> "level"
        fpm > 0 -> "climbing at ${spokenVerticalRate(fpm, unit)}"
        else -> "descending at ${spokenVerticalRate(-fpm, unit)}"
    }

    private fun spokenVerticalRate(fpm: Double, unit: AltitudeUnit): String = when (unit) {
        AltitudeUnit.FT -> String.format(Locale.US, "%.0f feet per minute", fpm)
        AltitudeUnit.M ->
            String.format(Locale.US, "%.1f meters per second", fpm / FEET_PER_MINUTE_PER_METER_PER_SECOND)
    }

    private fun roundsToOne(value: Double): Boolean = Math.round(value * 10).toInt() == 10
}
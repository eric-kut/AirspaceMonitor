package ca.trafficwatcher.domain

object Units {
    const val METERS_PER_FOOT = 0.3048
    const val KM_PER_NM = 1.852

    fun feetToMeters(ft: Double): Double = ft * METERS_PER_FOOT

    fun metersToFeet(m: Double): Double = m / METERS_PER_FOOT

    fun kmToNm(km: Double): Double = km / KM_PER_NM

    fun nmToKm(nm: Double): Double = nm * KM_PER_NM

    fun knotsToKmh(kt: Double): Double = kt * KM_PER_NM
}
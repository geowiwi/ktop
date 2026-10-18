package com.w1w1.ktop

/**
 * Cassette presets for the gearing editor (DeviceGearingActivity).
 * Teeth smallest -> largest, as listed by the manufacturers / retailers
 * (checked 2026-10-18 against bike-components.de, rosebikes, bike24,
 * rotorbike.com, sunshine-cassettes.com; GOLDIX from a reseller listing,
 * LTWOO from bikable.com). ZRACE: no full sequence found yet. Wheeltop doesn't sell cassettes; EDS works with
 * 3-14 speed cassettes from other brands. No 14-speed preset: no full
 * sprocket sequence could be verified - use "enter as list".
 *
 * ktop's rear gear 1 is the largest cog, so the editor stores them
 * reversed (rearGearsFor()).
 */
object Cassettes {
    data class Cassette(val name: String, val teeth: List<Int>) {
        val speeds: Int get() = teeth.size
        val label: String get() = "${speeds}s  $name  (${teeth.first()}-${teeth.last()})"
    }

    private fun c(name: String, vararg t: Int) = Cassette(name, t.toList())

    val ALL: List<Cassette> = listOf(
        // 10-speed
        c("Shimano CS-6700", 11, 12, 13, 14, 15, 17, 19, 21, 23, 25),
        c("Shimano CS-6700", 11, 12, 13, 14, 15, 17, 19, 21, 24, 28),
        c("Shimano CS-HG500-10", 11, 12, 14, 16, 18, 20, 22, 25, 28, 32),
        c("Shimano CS-HG500-10", 11, 13, 15, 17, 19, 21, 23, 26, 30, 34),
        // 11-speed
        c("Shimano CS-R7000", 11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28),
        c("Shimano CS-R7000", 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30),
        c("Shimano CS-R7000", 11, 12, 13, 14, 16, 18, 20, 22, 25, 28, 32),
        c("Shimano CS-HG800-11", 11, 13, 15, 17, 19, 21, 23, 25, 27, 30, 34),
        c("Shimano CS-M8000", 11, 13, 15, 17, 19, 21, 24, 28, 32, 37, 42),
        // 12-speed
        c("Shimano CS-R8100", 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30),
        c("Shimano CS-R8100", 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34),
        c("Shimano CS-HG710-12", 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 32, 36),
        c("Shimano CS-M8100", 10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 40, 45),
        c("Shimano CS-M8100", 10, 12, 14, 16, 18, 21, 24, 28, 33, 39, 45, 51),
        c("SRAM XG-1270", 10, 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 28),
        c("SRAM XG-1270", 10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33),
        c("SRAM XG-1270", 10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 36),
        c("SRAM XG-1271 XPLR", 10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44),
        c("SRAM Eagle XG-1295", 10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 50),
        c("SRAM Eagle XG-1295", 10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 52),
        c("Campagnolo 12s", 11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 26, 29),
        c("Campagnolo 12s", 11, 12, 13, 14, 15, 16, 17, 19, 22, 25, 28, 32),
        c("Campagnolo 12s", 11, 12, 13, 14, 15, 16, 17, 19, 22, 25, 29, 34),
        c("SUNSHINE-SZ HG road", 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30),
        c("SUNSHINE-SZ HG road", 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34),
        c("SUNSHINE-SZ HG road", 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 32, 36),
        c("SUNSHINE-SZ XDR", 10, 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 28),
        c("SUNSHINE-SZ XDR", 10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33),
        c("SUNSHINE-SZ XDR", 10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 36),
        c("SUNSHINE-SZ XDR", 10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44),
        c("SUNSHINE-SZ HG MTB", 11, 12, 13, 15, 17, 19, 21, 23, 25, 28, 33, 39),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 23, 25, 28, 32, 36, 40),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 23, 25, 28, 32, 36, 42),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 23, 25, 28, 32, 39, 45),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 24, 28, 32, 36, 40, 46),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 24, 28, 32, 36, 42, 50),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 18, 21, 24, 28, 32, 36, 40, 46, 52),
        c("SUNSHINE-SZ Micro Spline", 10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 50),
        c("SUNSHINE-SZ Micro Spline", 10, 12, 14, 16, 18, 21, 24, 28, 32, 36, 42, 52),
        c("SUNSHINE-SZ XD", 9, 11, 13, 15, 18, 21, 24, 28, 32, 36, 42, 50),
        c("GOLDIX road/gravel", 11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 25, 28),
        c("GOLDIX road/gravel", 11, 12, 13, 14, 15, 16, 17, 19, 21, 24, 27, 30),
        c("GOLDIX road/gravel", 11, 12, 13, 14, 15, 17, 19, 21, 23, 25, 28, 32),
        c("GOLDIX road/gravel", 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34),
        c("LTWOO A12", 11, 13, 15, 17, 19, 21, 24, 28, 32, 36, 42, 52),
        // 13-speed
        c("SUNSHINE-SZ HG MTB", 11, 12, 13, 15, 17, 19, 21, 23, 25, 28, 32, 36, 40),
        c("SUNSHINE-SZ HG MTB", 11, 12, 13, 15, 17, 19, 21, 23, 25, 28, 33, 39, 45),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 23, 25, 28, 32, 36, 40, 46),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 23, 25, 28, 32, 36, 42, 50),
        c("SUNSHINE-SZ HG MTB", 11, 13, 15, 17, 19, 21, 24, 28, 32, 36, 40, 46, 52),
        c("SRAM Red XPLR XG-1391", 10, 11, 12, 13, 15, 17, 19, 21, 24, 28, 32, 38, 46),
        c("Campagnolo Ekar", 9, 10, 11, 12, 13, 14, 16, 18, 20, 23, 27, 31, 36),
        c("Campagnolo Ekar", 9, 10, 11, 12, 13, 14, 16, 18, 21, 25, 30, 36, 42),
        c("Campagnolo Ekar GT", 10, 11, 12, 13, 14, 15, 17, 19, 22, 26, 32, 39, 44),
        c("Campagnolo Super Record 13", 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 23, 26, 29),
        c("Campagnolo Super Record 13", 10, 11, 12, 13, 14, 15, 16, 18, 20, 23, 26, 29, 33),
        c("Campagnolo Super Record 13", 11, 12, 13, 14, 15, 16, 17, 18, 20, 23, 26, 29, 32),
        c("Campagnolo Super Record 13", 11, 12, 13, 14, 15, 16, 18, 20, 23, 26, 29, 32, 36),
        c("Rotor 13s", 10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 31, 36),
        c("Rotor 13s", 10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33, 39),
        c("Rotor 13s", 10, 11, 12, 13, 15, 17, 19, 22, 25, 29, 34, 39, 46),
        c("Rotor 13s", 10, 11, 12, 13, 15, 17, 19, 22, 26, 31, 37, 44, 52),
    )

    /** ktop order: gear 1 = largest cog. */
    fun rearGearsFor(teeth: List<Int>): List<Int> = teeth.sortedDescending()
}

package com.tarteelwarsh.tilawa.logic.phonetizer

// Port fidele de Dani_app/tilawa_correction/qiraat_config.py

data class QiraatConfig(
    val maddTabii: Int = 2,
    val maddBadal: Int = 4,        // Warsh: 2, 4 ou 6
    val maddArid: Int = 6,         // Warsh: 2, 4 ou 6
    val maddMuttasil: Int = 6,     // Warsh: 6
    val maddMunfasil: Int = 6,     // Warsh: 6
    val maddLeen: Int = 4,
    val maddLazim: Int = 6,        // fixe en general mais variable selon riwaya
    val maddIwad: Int = 2,         // fixe
)

// Profils predefinis (miroir exact des instances de qiraat_config.py)
val WARSH_TAWASOT_ALBADAL_ICHBAA_ALARID = QiraatConfig(
    maddBadal = 4,
    maddArid = 6,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

val WARSH_TAWASOT_ALBADAL_TAWASOT_ALARID = QiraatConfig(
    maddBadal = 4,
    maddArid = 4,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

val WARSH_TAWASOT_ALBADAL_QASR_ALARID = QiraatConfig(
    maddBadal = 4,
    maddArid = 2,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

val WARSH_QASR_ALBADAL_ICHBAA_ALARID = QiraatConfig(
    maddBadal = 2,
    maddArid = 6,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

val WARSH_QASR_ALBADAL_TAWASOT_ALARID = QiraatConfig(
    maddBadal = 2,
    maddArid = 4,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

val WARSH_QASR_ALBADAL_QASR_ALARID = QiraatConfig(
    maddBadal = 2,
    maddArid = 2,
    maddMuttasil = 6,
    maddMunfasil = 6,
    maddLeen = 4,
)

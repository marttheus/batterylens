package com.batterylens.domain

/**
 * Explains why charging is slower than it could be. Android apps cannot set charger voltage or
 * current (that is negotiated between the phone's charging IC and the charger over USB PD/PPS),
 * so the useful thing an app can do is measure the real power and point at the limiting factor.
 */
enum class ChargeSpeed(val label: String) {
    UNKNOWN("Desconhecida"),
    SLOW("Lenta"),
    NORMAL("Normal"),
    FAST("Rápida"),
    SUPER_FAST("Super-rápida"),
}

enum class ChargeLimiter(val title: String, val advice: String) {
    CV_PHASE(
        "Fase final (acima de 80%)",
        "Acima de ~80% a bateria carrega em tensão constante e a corrente cai naturalmente. É normal e protege a bateria.",
    ),
    HOT(
        "Temperatura alta",
        "O sistema reduz a potência para esfriar a bateria. Tire a capinha, evite o sol e não use o aparelho enquanto carrega.",
    ),
    WARM(
        "Bateria morna",
        "Perto do limite térmico a potência pode oscilar. Um ambiente mais fresco ajuda a manter a velocidade.",
    ),
    SCREEN_ON(
        "Tela ligada",
        "Parte da energia vai para o uso do aparelho e o sistema limita a carga para controlar o calor. Desligue a tela para carregar mais rápido.",
    ),
    USB_PORT(
        "Fonte USB de baixa potência",
        "Portas USB de computador/carro entregam pouca potência. Use um carregador de tomada USB-C PD.",
    ),
    WIRELESS(
        "Carregamento sem fio",
        "O carregamento sem fio é mais lento e esquenta mais. Use o cabo para máxima velocidade.",
    ),
    WEAK_CHARGER(
        "Carregador ou cabo limitado",
        "A potência está baixa sem outro motivo aparente. Para carregamento super-rápido use um carregador USB-C PD com PPS, um cabo compatível (5 A para potências altas) e ative o carregamento rápido nas configurações de bateria do sistema.",
    ),
}

data class ChargeDiagnosis(
    val speed: ChargeSpeed,
    val powerW: Float?,
    val limiters: List<ChargeLimiter>,
)

object ChargeDiagnostics {
    const val HOT_C = 40f
    const val WARM_C = 37f

    fun speedFor(powerW: Float?): ChargeSpeed = when {
        powerW == null || powerW <= 0f -> ChargeSpeed.UNKNOWN
        powerW < 7.5f -> ChargeSpeed.SLOW
        powerW < 15f -> ChargeSpeed.NORMAL
        powerW < 25f -> ChargeSpeed.FAST
        else -> ChargeSpeed.SUPER_FAST
    }

    /**
     * @param plugType BatteryManager.BATTERY_PLUGGED_* value (1 = AC, 2 = USB, 4 = wireless).
     */
    fun diagnose(powerW: Float?, level: Int, temperatureC: Float, screenOn: Boolean, plugType: Int): ChargeDiagnosis {
        val speed = speedFor(powerW)
        val limiters = buildList {
            if (level >= BatteryMath.CV_PHASE_LEVEL) add(ChargeLimiter.CV_PHASE)
            when {
                temperatureC >= HOT_C -> add(ChargeLimiter.HOT)
                temperatureC >= WARM_C -> add(ChargeLimiter.WARM)
            }
            if (screenOn) add(ChargeLimiter.SCREEN_ON)
            when (plugType) {
                2 -> add(ChargeLimiter.USB_PORT)
                4 -> add(ChargeLimiter.WIRELESS)
            }
            // Nothing else explains a slow wired charge: blame the charger/cable.
            if (isEmpty() && speed != ChargeSpeed.UNKNOWN && (powerW ?: 0f) < 15f) add(ChargeLimiter.WEAK_CHARGER)
        }
        return ChargeDiagnosis(speed, powerW, limiters)
    }
}

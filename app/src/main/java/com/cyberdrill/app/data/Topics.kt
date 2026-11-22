package com.cyberdrill.app.data

/** The six topics of the study path, mirroring the owner's NetAcad route. */
object Topics {
    val all: List<Topic> = listOf(
        Topic(
            id = "governance",
            nameEs = "Gobernanza y cumplimiento",
            nameEn = "Governance & compliance",
            descEs = "Políticas, procedimientos, auditorías y leyes que ordenan la seguridad.",
            descEn = "Policies, procedures, audits and laws that organize security.",
            monogram = "GC"
        ),
        Topic(
            id = "threats",
            nameEs = "Amenazas y vulnerabilidades",
            nameEn = "Threats & vulnerabilities",
            descEs = "Phishing, ransomware, días cero y las debilidades que aprovechan.",
            descEn = "Phishing, ransomware, zero-days and the weaknesses they abuse.",
            monogram = "AV"
        ),
        Topic(
            id = "network",
            nameEs = "Defensa de la red",
            nameEn = "Network defense",
            descEs = "Cortafuegos, segmentación, VPN y detección de intrusiones.",
            descEn = "Firewalls, segmentation, VPNs and intrusion detection.",
            monogram = "DR"
        ),
        Topic(
            id = "endpoint",
            nameEs = "Seguridad de endpoints",
            nameEn = "Endpoint security",
            descEs = "Parcheo, MFA, mínimo privilegio y copias de seguridad.",
            descEn = "Patching, MFA, least privilege and backups.",
            monogram = "EP"
        ),
        Topic(
            id = "incident",
            nameEs = "Respuesta a incidentes",
            nameEn = "Incident response",
            descEs = "Contener, erradicar, recuperar y aprender de cada incidente.",
            descEn = "Contain, eradicate, recover and learn from every incident.",
            monogram = "RI"
        ),
        Topic(
            id = "crypto",
            nameEs = "Criptografía básica",
            nameEn = "Cryptography basics",
            descEs = "Cifrado simétrico y asimétrico, hash, firmas y TLS.",
            descEn = "Symmetric and asymmetric encryption, hashing, signatures and TLS.",
            monogram = "CR"
        )
    )

    fun byId(id: String): Topic = all.firstOrNull { it.id == id } ?: all.first()
}

# Translation review for 1.2

Strings added or changed for the 1.2 hardening work (branch `hardening-1.2`). All Arabic,
French and Dutch texts here are **machine translations** and need a native speaker: Arabic
by the owner, French and Dutch by native speakers. `%1$d` and `%2$d` are filled in by the
app and must stay as they are.

Each task adds a section. To correct a translation, edit the string file named in the
section, not this table.

## Task 0.2: TLS connections that can't be read (`res/values*/strings_settings.xml`, 1 string)

Shown in Settings › Advanced › Diagnostics, under *Server-name blocking (SNI)*. The second
sentence is new.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `diag_sni_detail` | Connections stopped by their TLS server name since protection was turned on: %1$d. Reset because their TLS start could not be read (Standard and Strict): %2$d. | الاتصالات التي أُوقفت بسبب اسم خادم TLS منذ تشغيل الحماية: %1$d. اتصالات أُعيد ضبطها لأن بداية TLS فيها لم تكن قابلة للقراءة (القياسي والصارم): %2$d. | Connexions arrêtées par leur nom de serveur TLS depuis l’activation de la protection : %1$d. Réinitialisées car leur début TLS était illisible (Standard et Strict) : %2$d. | Verbindingen gestopt op hun TLS-servernaam sinds de bescherming aanstaat: %1$d. Verbroken omdat hun TLS-begin onleesbaar was (Standaard en Streng): %2$d. |

## Task 1.6: bundled malware list (2 strings)

`ui_diag_list_snapshot` is in `res/values*/strings_advanced_ui.xml` (Settings › Advanced ›
Diagnostics, list of names per list; `%1$s` is the list name, `%2$s` a date as 2026-11-05).
`lic_desc_hagezi` is in `res/values*/strings_licenses.xml` (Settings › About › Open-source
licenses); its second sentence is new.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_diag_list_snapshot` | %1$s (built-in copy from %2$s, used until the first download) | %1$s (نسخة مدمجة من %2$s، تُستخدم حتى أول تنزيل) | %1$s (copie intégrée du %2$s, utilisée jusqu’au premier téléchargement) | %1$s (ingebouwde kopie van %2$s, gebruikt tot de eerste download) |
| `lic_desc_hagezi` | Pro, Pro mini, Threat Intelligence Feeds mini, DoH and DoH IP lists. A copy of Threat Intelligence Feeds mini is included in the app, for the time before the first download. | قوائم Pro وPro mini وThreat Intelligence Feeds mini وDoH وعناوين IP لخوادم DoH. نسخة من Threat Intelligence Feeds mini مضمّنة في التطبيق، للفترة التي تسبق أول تنزيل. | Listes Pro, Pro mini, Threat Intelligence Feeds mini, DoH et adresses IP DoH. Une copie de Threat Intelligence Feeds mini est incluse dans l’application, pour la période avant le premier téléchargement. | De lijsten Pro, Pro mini, Threat Intelligence Feeds mini, DoH en DoH-IP-adressen. Een kopie van Threat Intelligence Feeds mini zit in de app, voor de tijd vóór de eerste download. |

## Task 1.4: names behind Encrypted Client Hello (`res/values*/strings_advanced_ui.xml`, 3 strings)

Settings › Advanced › Network, under the QUIC switch. "Encrypted Client Hello" is the
protocol's name and stays in English. `ui_net_ech_strict_only` is added after the
description when the level is not Strict.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_net_ech` | Read names behind Encrypted Client Hello | قراءة الأسماء خلف Encrypted Client Hello | Lire les noms derrière Encrypted Client Hello | Namen achter Encrypted Client Hello lezen |
| `ui_net_ech_sub` | Lets CoreLock see the names of encrypted browser connections. Your network can see these names too. | يتيح لـ CoreLock رؤية أسماء اتصالات المتصفح المشفّرة. ويمكن لشبكتك أيضًا أن ترى هذه الأسماء. | Permet à CoreLock de voir les noms des connexions chiffrées du navigateur. Votre réseau peut aussi voir ces noms. | Laat CoreLock de namen van versleutelde browserverbindingen zien. Je netwerk kan deze namen dan ook zien. |
| `ui_net_ech_strict_only` | Works only at Strict protection. | يعمل في مستوى الحماية الصارم فقط. | Fonctionne uniquement avec la protection Stricte. | Werkt alleen bij de bescherming Streng. |

## Task 2.3: CA rotation and the new certificate name (`enterprise/res/values*/strings_mitm.xml`, `main/res/values*/strings_dashboard.xml`)

New: the renewal dialog, shown when the user's 90-day consent has ended while inspection
is on. Changed: texts that name the certificate now say "CoreLock HTTPS Inspection CA"
and, where users must remove old ones, also the old name "PrivacyGuard Enterprise CA".
Certificate and file names stay in English (they are what Android shows).

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `mitm_consent_renew_title` | Renew traffic inspection | تجديد فحص حركة البيانات | Renouveler l’inspection du trafic | Verkeersinspectie verlengen |
| `mitm_consent_renew_body` | Your consent to HTTPS inspection lasts 90 days and has ended. Inspection is paused until you decide. If you renew, inspection continues for another 90 days. For your safety the app also replaces its certificate: Android will ask you to install the new one, and the old one stops working. You can then remove the old one from Settings → Security → Certificates. | تدوم موافقتك على فحص HTTPS مدة 90 يومًا وقد انتهت. الفحص متوقف حتى تقرّر. إذا جدّدت، يستمر الفحص 90 يومًا أخرى. ولأمانك يستبدل التطبيق شهادته أيضًا: سيطلب منك Android تثبيت الشهادة الجديدة، وتتوقف القديمة عن العمل. يمكنك بعدها إزالة القديمة من الإعدادات ← الأمان ← الشهادات. | Votre consentement à l’inspection HTTPS dure 90 jours et a pris fin. L’inspection est en pause jusqu’à votre décision. Si vous renouvelez, l’inspection continue pendant 90 jours de plus. Pour votre sécurité, l’app remplace aussi son certificat : Android vous demandera d’installer le nouveau, et l’ancien ne fonctionnera plus. Vous pourrez ensuite supprimer l’ancien dans Paramètres → Sécurité → Certificats. | Je toestemming voor HTTPS-inspectie geldt 90 dagen en is verlopen. De inspectie is gepauzeerd tot je beslist. Als je verlengt, gaat de inspectie nog 90 dagen door. Voor je veiligheid vervangt de app ook zijn certificaat: Android vraagt je het nieuwe te installeren, en het oude werkt niet meer. Daarna kun je het oude verwijderen via Instellingen → Beveiliging → Certificaten. |
| `mitm_consent_renew_confirm` | Renew and install certificate | تجديد وتثبيت الشهادة | Renouveler et installer le certificat | Verlengen en certificaat installeren |
| `mitm_stale_cas` (all plural forms) | … remove every “PrivacyGuard Enterprise CA” and every “CoreLock HTTPS Inspection CA” except the newest. | … وأزل كل «PrivacyGuard Enterprise CA» وكل «CoreLock HTTPS Inspection CA» ما عدا الأحدث. | … supprimez chaque « PrivacyGuard Enterprise CA » et chaque « CoreLock HTTPS Inspection CA » sauf le plus récent. | … verwijder elke ‘PrivacyGuard Enterprise CA’ en elke ‘CoreLock HTTPS Inspection CA’ behalve de nieuwste. |
| `mitm_install_steps` (step 3) | Older CoreLock-CA and PrivacyGuard-CA files there are from earlier installs and no longer work. | ملفات CoreLock-CA وPrivacyGuard-CA الأقدم هناك … | Les anciens fichiers CoreLock-CA et PrivacyGuard-CA qui s’y trouvent … | Oudere CoreLock-CA- en PrivacyGuard-CA-bestanden daar … |
| `dash_card_decrypt_on_desc` | Apps that trust the CoreLock certificate have their HTTPS content decrypted and stored on this phone | التطبيقات التي تثق بشهادة CoreLock … | Pour les apps qui font confiance au certificat CoreLock, … | Bij apps die het CoreLock-certificaat vertrouwen, … |

All Arabic, French and Dutch text above is machine-made: Arabic for the owner to check,
French and Dutch for native speakers. The step-3 text was also wrong before: files were
named "PrivacyGuard-CA-…", not "privacyguard_ca".

## Decision 3: Home card after the update from 1.1 (`res/values*/strings_ui.xml`, 2 strings)

Shown once on Home to users who had HTTPS inspection on in 1.1, until they install the new
certificate or dismiss the card. Home is an everyday screen, so the certificate is not named
(BannedWordsTest); the HTTPS-inspection screen names it.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_attention_inspection_cert` | HTTPS inspection needs CoreLock’s new certificate. Until you install it, nothing is inspected. The old certificate no longer works. | يحتاج فحص HTTPS إلى شهادة CoreLock الجديدة. لن يُفحص شيء حتى تثبّتها. لم تعد الشهادة القديمة تعمل. | L’inspection HTTPS a besoin du nouveau certificat de CoreLock. Tant que vous ne l’avez pas installé, rien n’est inspecté. L’ancien certificat ne fonctionne plus. | HTTPS-inspectie heeft het nieuwe certificaat van CoreLock nodig. Tot je het installeert, wordt niets geïnspecteerd. Het oude certificaat werkt niet meer. |
| `ui_install_certificate` | Install certificate | تثبيت الشهادة | Installer le certificat | Certificaat installeren |

Arabic, French and Dutch are machine-made.

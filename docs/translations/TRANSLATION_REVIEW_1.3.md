# Translation review for 1.3

Strings added or changed in the 1.3 bug-fix round (branch `bug-fixes`). All Arabic, French
and Dutch texts here are **machine translations** and need a native speaker: Arabic by the
owner, French and Dutch by native speakers. `%1$d`, `%2$d` and `%3$d` are filled in by the
app and must stay as they are.

To correct a translation, edit the string file named in the section, not this table.

## Diagnostics: limits and unknown apps (`res/values*/strings_settings.xml`, 6 strings)

Settings › Advanced › Diagnostics. New rows.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `diag_limits` | Connection safety limits | حدود الأمان للاتصالات | Limites de sécurité des connexions | Veiligheidslimieten voor verbindingen |
| `diag_limits_detail` | Since protection was turned on. Broken-up packets dropped (they hide which app and port they belong to): %1$d. New connections refused because too many were open: %2$d. Idle connections closed early to make room: %3$d. | منذ تشغيل الحماية. حزم مجزّأة أُسقطت (تُخفي التطبيق والمنفذ اللذين تتبعهما): %1$d. اتصالات جديدة رُفضت لأن عدد الاتصالات المفتوحة كان كبيرًا جدًا: %2$d. اتصالات خاملة أُغلقت مبكرًا لإفساح المجال: %3$d. | Depuis l’activation de la protection. Paquets fragmentés rejetés (ils cachent l’app et le port auxquels ils appartiennent) : %1$d. Nouvelles connexions refusées car trop étaient ouvertes : %2$d. Connexions inactives fermées plus tôt pour faire de la place : %3$d. | Sinds de bescherming aanstaat. Opgeknipte pakketten weggegooid (ze verbergen bij welke app en poort ze horen): %1$d. Nieuwe verbindingen geweigerd omdat er te veel open waren: %2$d. Inactieve verbindingen vroeger gesloten om ruimte te maken: %3$d. |
| `diag_unknown_app` | Connections from an unknown app | اتصالات من تطبيق غير معروف | Connexions d’une app inconnue | Verbindingen van een onbekende app |
| `diag_unknown_app_detail` | Connections since protection was turned on whose app could not be identified: %1$d. Your rules for single apps can’t apply to them; rules for sites and addresses still do. | اتصالات منذ تشغيل الحماية تعذّر تحديد تطبيقها: %1$d. لا يمكن تطبيق قواعدك الخاصة بتطبيق معيّن عليها؛ أما قواعد المواقع والعناوين فتبقى سارية. | Connexions depuis l’activation de la protection dont l’app n’a pas pu être identifiée : %1$d. Vos règles pour une app précise ne peuvent pas s’y appliquer ; les règles de sites et d’adresses, si. | Verbindingen sinds de bescherming aanstaat waarvan de app niet herkend werd: %1$d. Je regels voor één app gelden er niet voor; regels voor sites en adressen wel. |
| `diag_inspection_limit` | HTTPS inspection limit | حدّ فحص HTTPS | Limite de l’inspection HTTPS | Limiet van HTTPS-inspectie |
| `diag_inspection_limit_detail` | Connections passed on without decryption because inspection was at its limit: %1$d. Blocking by site name still applied. | اتصالات مُرّرت دون فك التشفير لأن الفحص بلغ حدّه: %1$d. بقي الحظر حسب اسم الموقع ساريًا. | Connexions transmises sans déchiffrement car l’inspection avait atteint sa limite : %1$d. Le blocage par nom de site s’est appliqué quand même. | Verbindingen doorgegeven zonder ontsleuteling omdat de inspectie op haar limiet zat: %1$d. Blokkeren op sitenaam gold nog steeds. |

## Diagnostics: handled errors and unencrypted web (`res/values*/strings_settings.xml`, 4 strings)

Settings › Advanced › Diagnostics. New rows.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `diag_handled_errors` | Handled errors | أخطاء تمت معالجتها | Erreurs gérées | Afgehandelde fouten |
| `diag_handled_errors_detail` | Since protection was turned on. Failed list download attempts (they are retried): %1$d. Failed app lookups (the app shows as unknown): %2$d. HTTPS inspection connections that did not close cleanly: %3$d. | منذ تشغيل الحماية. محاولات تنزيل قوائم فشلت (تُعاد المحاولة): %1$d. محاولات فشلت في معرفة التطبيق (يظهر التطبيق كغير معروف): %2$d. اتصالات فحص HTTPS لم تُغلق بشكل سليم: %3$d. | Depuis l’activation de la protection. Tentatives de téléchargement de listes échouées (elles sont réessayées) : %1$d. Identifications d’app échouées (l’app apparaît comme inconnue) : %2$d. Connexions d’inspection HTTPS mal fermées : %3$d. | Sinds de bescherming aanstaat. Mislukte pogingen om lijsten te downloaden (ze worden opnieuw geprobeerd): %1$d. Mislukte app-herkenningen (de app staat als onbekend): %2$d. HTTPS-inspectieverbindingen die niet netjes sloten: %3$d. |
| `diag_http` | Site-name blocking (unencrypted web) | الحظر حسب اسم الموقع (الويب غير المشفّر) | Blocage par nom de site (web non chiffré) | Blokkeren op sitenaam (onversleuteld web) |
| `diag_http_detail` | Unencrypted web requests stopped by their site name since protection was turned on: %1$d. Reset because their site name could not be read (Standard and Strict): %2$d. | طلبات ويب غير مشفّرة أُوقفت بسبب اسم موقعها منذ تشغيل الحماية: %1$d. أُعيد ضبطها لأن اسم موقعها تعذّرت قراءته (المستوى القياسي والصارم): %2$d. | Requêtes web non chiffrées arrêtées par leur nom de site depuis l’activation de la protection : %1$d. Réinitialisées car leur nom de site était illisible (Standard et Strict) : %2$d. | Onversleutelde webverzoeken gestopt op hun sitenaam sinds de bescherming aanstaat: %1$d. Verbroken omdat hun sitenaam niet leesbaar was (Standaard en Streng): %2$d. |

## Connection details: "Stopped by" (`res/values*/strings_connections.xml`, 1 string)

Shown in a blocked connection's details. New value.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `conn_blocked_by_http` | Site name in an unencrypted request: a blocked name, reached without DNS | اسم الموقع في طلب غير مشفّر: اسم محظور، وصل إليه التطبيق دون DNS | Nom du site dans une requête non chiffrée : un nom bloqué, atteint sans DNS | Sitenaam in een onversleuteld verzoek: een geblokkeerde naam, bereikt zonder DNS |

## Rules: what a name rule covers (`res/values*/strings_rules.xml`, 1 changed string)

Hint under the name field when adding a rule. Changed because Block rules now cover
subdomains (ADR 0017).

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `rules_domain_exact` | Block covers this name and every subdomain. Allow covers this exact name only; write *.example.com to allow every subdomain. | الحظر يشمل هذا الاسم وكل نطاقاته الفرعية. السماح يشمل هذا الاسم بالضبط فقط؛ اكتب *.example.com للسماح بكل النطاقات الفرعية. | Bloquer couvre ce nom et tous ses sous-domaines. Autoriser couvre seulement ce nom exact ; écrivez *.example.com pour autoriser tous les sous-domaines. | Blokkeren geldt voor deze naam en alle subdomeinen. Toestaan geldt alleen voor deze exacte naam; schrijf *.example.com om alle subdomeinen toe te staan. |

## Home: notifications are off (`res/values*/strings_ui.xml`, 1 new string)

Home attention card, shown while CoreLock's notifications (or its alerts channel) are off.
Its button is the existing "Open settings".

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_attention_notifications` | Notifications are off. If protection stops, CoreLock can’t warn you. | الإشعارات متوقفة. إذا توقفت الحماية، لن يستطيع CoreLock تنبيهك. | Les notifications sont désactivées. Si la protection s’arrête, CoreLock ne pourra pas vous prévenir. | Meldingen staan uit. Als de bescherming stopt, kan CoreLock je niet waarschuwen. |

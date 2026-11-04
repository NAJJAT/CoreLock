# Translation review for 1.1

Strings added for the 1.1 redesign (`docs/ux/DESIGN_SPEC_1.1.md`, branch `redesign-1.1`).
All Arabic, French and Dutch texts here are **machine translations** and need a native
speaker: Arabic by the owner, French and Dutch by native speakers. `%d` and `%1$s` are
filled in by the app and must stay as they are. Plurals list each quantity form
(Arabic uses zero/one/two/few/many/other; French one/many/other).

Each phase adds a section. To correct a translation, edit the string file named in the
section, not this table: the table is generated from those files.

What to check:
- It reads naturally to someone who is not technical (the 1.1 writing rules: plain words,
  buttons are verbs, no ALL CAPS).
- Button labels still fit: they wrap, but a shorter natural word is better.
- Arabic: `ui_allow_everywhere` was changed from "اسمح للجميع" to "اسمح لكل التطبيقات"
  during Phase 2, because "للجميع" (for everyone) did not say it is about apps.

## Phase 2: design system components (`res/values*/strings_ui.xml`, 69 strings)

Some of these are already shown in the component catalog only and get used by the
screens of Phases 3 to 6 (Home, Activity, Apps, Settings).

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_protection` | Protection | الحماية | Protection | Bescherming |
| `ui_technical_details` | Technical details | التفاصيل التقنية | Détails techniques | Technische details |
| `ui_expanded` | Expanded | مفتوحة | Développé | Uitgeklapt |
| `ui_collapsed` | Collapsed | مطوية | Réduit | Ingeklapt |
| `ui_try_again` | Try again | حاول مرة أخرى | Réessayer | Opnieuw proberen |
| `ui_dismiss` | Dismiss | إخفاء | Masquer | Verbergen |
| `ui_close` | Close | إغلاق | Fermer | Sluiten |
| `ui_loading` | Loading | جارٍ التحميل | Chargement | Laden |
| `ui_learn_more` | Learn more | اعرف المزيد | En savoir plus | Meer informatie |
| `ui_warning` | Warning | تحذير | Avertissement | Waarschuwing |
| `ui_open_settings` | Open settings | افتح الإعدادات | Ouvrir les paramètres | Instellingen openen |
| `ui_turn_on` | Turn on | شغّل الحماية | Activer | Aanzetten |
| `ui_hero_on` | You're protected | أنت محمي | Vous êtes protégé | Je bent beschermd |
| `ui_hero_off` | Protection is off | الحماية متوقفة | Protection désactivée (shortened in Phase 3b: the long form needed 3 lines at 200 %) | Bescherming staat uit |
| `ui_hero_starting` | Starting… | جارٍ التشغيل… | Démarrage… | Starten… |
| `ui_hero_stopped` | Protection stopped | توقفت الحماية | La protection s'est arrêtée | Bescherming is gestopt |
| `ui_hero_sub_minimal` | Blocking malware · Minimal | يحظر البرامج الضارة · الحد الأدنى | Bloque les logiciels malveillants · Minimal | Blokkeert malware · Minimaal |
| `ui_hero_sub_standard` | Blocking ads and trackers · Standard | يحظر الإعلانات وأدوات التتبع · قياسي | Bloque les pubs et les traqueurs · Standard | Blokkeert advertenties en trackers · Standaard |
| `ui_hero_sub_strict` | Blocking ads, trackers and more · Strict | يحظر الإعلانات وأدوات التتبع وأكثر · صارم | Bloque les pubs, les traqueurs et plus · Strict | Blokkeert advertenties, trackers en meer · Streng |
| `ui_hero_off_sub` | Turn it on to block ads, trackers and malware. | شغّلها لحظر الإعلانات وأدوات التتبع والبرامج الضارة. | Activez-la pour bloquer les pubs, les traqueurs et les logiciels malveillants. | Zet het aan om advertenties, trackers en malware te blokkeren. |
| `ui_hero_starting_sub` | This takes a few seconds. | يستغرق ذلك بضع ثوانٍ. | Cela prend quelques secondes. | Dit duurt een paar seconden. |
| `ui_hero_stopped_sub` | Another VPN may have taken over. | ربما تولّى تطبيق VPN آخر الاتصال. | Un autre VPN a peut-être pris le relais. | Misschien heeft een andere VPN het overgenomen. |
| `ui_protection_level` | Protection level | مستوى الحماية | Niveau de protection | Beschermingsniveau |
| `ui_level_minimal_desc` | Blocks malware. Nothing breaks. | يحظر البرامج الضارة. لا يتعطل شيء. | Bloque les logiciels malveillants. Rien ne se casse. | Blokkeert malware. Er gaat niets kapot. |
| `ui_level_standard_desc` | Blocks ads and trackers. Recommended. | يحظر الإعلانات وأدوات التتبع. موصى به. | Bloque les pubs et les traqueurs. Recommandé. | Blokkeert advertenties en trackers. Aanbevolen. |
| `ui_level_strict_desc` | Blocks more. Some apps or sites may break. | يحظر أكثر. قد تتعطل بعض التطبيقات أو المواقع. | Bloque davantage. Certaines applis ou certains sites peuvent ne plus marcher. | Blokkeert meer. Sommige apps of sites werken misschien niet meer. |
| `ui_blocked_today` | Blocked today | المحظور اليوم | Bloqués aujourd'hui | Vandaag geblokkeerd |
| `ui_something_not_working` | Something not working? | هل هناك شيء لا يعمل؟ | Quelque chose ne marche pas ? | Werkt er iets niet? |
| `ui_attention_private_dns` | Private DNS is on. Websites and apps may not load until you turn it off. | DNS الخاص مفعّل. قد لا تفتح المواقع والتطبيقات حتى توقفه. | Le DNS privé est activé. Sites et applis risquent de ne pas se charger tant qu'il reste activé. | Privé-DNS staat aan. Websites en apps laden misschien niet tot je het uitzet. |
| `ui_filter_blocked` | Blocked | المحظور | Bloqués | Geblokkeerd |
| `ui_filter_all` | All | الكل | Tout | Alles |
| `ui_reason_ads` | Blocked · Ads and trackers list | محظور · قائمة الإعلانات وأدوات التتبع | Bloqué · Liste pubs et traqueurs | Geblokkeerd · Lijst met advertenties en trackers |
| `ui_reason_malware` | Blocked · Malware list | محظور · قائمة البرامج الضارة | Bloqué · Liste logiciels malveillants | Geblokkeerd · Malwarelijst |
| `ui_reason_rule` | Blocked · Your rule | محظور · قاعدتك | Bloqué · Votre règle | Geblokkeerd · Je eigen regel |
| `ui_reason_allowed` | Allowed | مسموح | Autorisé | Toegestaan |
| `ui_allow_for_app` | Allow for this app | اسمح لهذا التطبيق | Autoriser pour cette appli | Toestaan voor deze app |
| `ui_allow_everywhere` | Allow everywhere | اسمح لكل التطبيقات | Autoriser partout | Overal toestaan |
| `ui_keep_blocked` | Keep blocked | أبقِه محظورًا | Laisser bloqué | Geblokkeerd laten |
| `ui_remove_my_rule` | Remove my rule | احذف قاعدتي | Supprimer ma règle | Mijn regel verwijderen |
| `ui_dns_allow_everywhere_only` | This was blocked before the app connected, so it can only be allowed for all apps. | حُظر هذا قبل أن يتصل التطبيق، لذلك لا يمكن السماح به إلا لكل التطبيقات. | Ceci a été bloqué avant que l'appli se connecte. On ne peut donc l'autoriser que pour toutes les applis. | Dit werd geblokkeerd voordat de app verbinding maakte, dus het kan alleen voor alle apps worden toegestaan. |
| `ui_empty_activity` | Nothing blocked yet. CoreLock will show blocked ads and trackers here. | لم يُحظر شيء بعد. سيعرض CoreLock هنا الإعلانات وأدوات التتبع المحظورة. | Rien n'a encore été bloqué. CoreLock affichera ici les pubs et traqueurs bloqués. | Nog niets geblokkeerd. CoreLock laat hier geblokkeerde advertenties en trackers zien. |
| `ui_error_activity` | Couldn't load activity. | تعذّر تحميل النشاط. | Impossible de charger l'activité. | Activiteit kon niet worden geladen. |
| `ui_app_allowed` | Allowed | مسموح | Autorisée | Toegestaan |
| `ui_app_blocked_background` | Blocked in background | محظور في الخلفية | Bloquée en arrière-plan | Geblokkeerd op de achtergrond |
| `ui_app_blocked` | Blocked | محظور | Bloquée | Geblokkeerd |
| `ui_app_blocked_background_desc` | Can use the internet only while you're using it. | يستخدم الإنترنت فقط أثناء استخدامك له. | Peut utiliser Internet seulement quand vous l'utilisez. | Kan internet alleen gebruiken terwijl jij de app gebruikt. |
| `ui_app_blocked_desc` | Can't use the internet. | لا يمكنه استخدام الإنترنت. | Ne peut pas utiliser Internet. | Kan internet niet gebruiken. |
| `ui_risk_watch` | Could watch you | قد يراقبك | Peut vous surveiller | Kan je in de gaten houden |
| `ui_risk_watch_desc` | This app has permissions and behaviour often seen in apps that track a person: %1$s. If you didn't install it yourself, check it. | لهذا التطبيق أذونات وسلوك يظهران غالبًا في تطبيقات تتعقّب الأشخاص: %1$s. إن لم تثبّته بنفسك، فتحقّق منه. | Cette appli a des autorisations et un comportement souvent vus dans les applis qui suivent une personne : %1$s. Si vous ne l'avez pas installée vous-même, vérifiez-la. | Deze app heeft rechten en gedrag die vaak voorkomen bij apps die iemand volgen: %1$s. Heb je hem niet zelf geïnstalleerd? Controleer hem dan. |
| `ui_risk_high` | High risk | خطورة عالية | Risque élevé | Hoog risico |
| `ui_risk_high_desc` | CoreLock saw risky connections from this app in the last 24 hours. Open the app to see them. | رصد CoreLock اتصالات خطرة من هذا التطبيق خلال آخر 24 ساعة. افتح التطبيق لرؤيتها. | CoreLock a vu des connexions risquées de cette appli ces dernières 24 heures. Ouvrez l'appli pour les voir. | CoreLock zag de afgelopen 24 uur riskante verbindingen van deze app. Open de app om ze te zien. |
| `ui_risk_unprotected` | Sends unprotected data | يرسل بيانات غير محمية | Envoie des données non protégées | Verstuurt onbeschermde gegevens |
| `ui_risk_unprotected_desc` | Some of this app's connections weren't encrypted, so others on the network could read them. | بعض اتصالات هذا التطبيق لم تكن مشفّرة، لذلك يمكن لآخرين على الشبكة قراءتها. | Certaines connexions de cette appli n'étaient pas chiffrées : d'autres personnes sur le réseau pouvaient les lire. | Sommige verbindingen van deze app waren niet versleuteld, dus anderen op het netwerk konden ze lezen. |
| `ui_risk_trackers` | Uses trackers | يستخدم أدوات تتبع | Utilise des traqueurs | Gebruikt trackers |
| `ui_risk_trackers_desc` | CoreLock blocked tracker or ad connections from this app. That's normal for many apps. | حظر CoreLock اتصالات تتبع أو إعلانات من هذا التطبيق. هذا أمر عادي في كثير من التطبيقات. | CoreLock a bloqué des connexions de pub ou de suivi de cette appli. C'est courant pour beaucoup d'applis. | CoreLock blokkeerde tracker- of advertentieverbindingen van deze app. Dat is normaal voor veel apps. |
| `ui_risk_background` | Active in the background | نشط في الخلفية | Active en arrière-plan | Actief op de achtergrond |
| `ui_risk_background_desc` | This app used the internet while you weren't using it. | استخدم هذا التطبيق الإنترنت عندما لم تكن تستخدمه. | Cette appli a utilisé Internet quand vous ne l'utilisiez pas. | Deze app gebruikte internet terwijl jij hem niet gebruikte. |
| `ui_risk_none` | No activity | لا نشاط | Aucune activité | Geen activiteit |
| `ui_risk_none_desc` | This app didn't use the internet in the last 24 hours. | لم يستخدم هذا التطبيق الإنترنت خلال آخر 24 ساعة. | Cette appli n'a pas utilisé Internet ces dernières 24 heures. | Deze app heeft de afgelopen 24 uur geen internet gebruikt. |
| `ui_risk_normal` | Looks normal | يبدو طبيعيًا | Semble normale | Ziet er normaal uit |
| `ui_risk_normal_desc` | Nothing unusual in the last 24 hours. | لا شيء غير عادي خلال آخر 24 ساعة. | Rien d'inhabituel ces dernières 24 heures. | Niets ongewoons in de afgelopen 24 uur. |
| `ui_advanced` | Advanced | متقدم | Avancé | Geavanceerd |
| `ui_advanced_sub` | For experts. Wrong settings can break your connection. | للخبراء. الإعدادات الخاطئة قد تقطع اتصالك. | Pour les experts. De mauvais réglages peuvent couper votre connexion. | Voor experts. Verkeerde instellingen kunnen je verbinding verbreken. |
| `ui_restart_after_crash` | Restart after a crash | إعادة التشغيل بعد التعطل | Redémarrer après un plantage | Herstarten na een crash |
| `ui_restart_after_crash_sub` | Turn protection back on by itself | إعادة تشغيل الحماية تلقائيًا | Réactiver la protection automatiquement | Bescherming vanzelf weer aanzetten |
| `ui_clear_all_data` | Clear all data | امسح كل البيانات | Effacer toutes les données | Alle gegevens wissen |
| `ui_what_corelock_cant` | What CoreLock can and can't do | ما يستطيعه CoreLock وما لا يستطيعه | Ce que CoreLock peut faire ou non | Wat CoreLock wel en niet kan |
| `ui_what_corelock_cant_body` | CoreLock blocks ads, trackers and malware for the apps on this phone. It can't block ads inside YouTube, Instagram, Snapchat, TikTok or Facebook, because they come from the same servers as the videos and posts. | يحظر CoreLock الإعلانات وأدوات التتبع والبرامج الضارة للتطبيقات على هذا الهاتف. لا يستطيع حظر الإعلانات داخل YouTube وInstagram وSnapchat وTikTok وFacebook، لأنها تأتي من الخوادم نفسها التي تأتي منها المقاطع والمنشورات. | CoreLock bloque les pubs, les traqueurs et les logiciels malveillants pour les applis de ce téléphone. Il ne peut pas bloquer les pubs dans YouTube, Instagram, Snapchat, TikTok ou Facebook, car elles viennent des mêmes serveurs que les vidéos et les publications. | CoreLock blokkeert advertenties, trackers en malware voor de apps op deze telefoon. Het kan geen advertenties blokkeren in YouTube, Instagram, Snapchat, TikTok of Facebook, omdat die van dezelfde servers komen als de video's en berichten. |
| `ui_blocked_today_n` | one: %d blocked today<br>other: %d blocked today | zero: لم يُحظر شيء اليوم<br>one: حُظر اتصال واحد اليوم<br>two: حُظر اتصالان اليوم<br>few: حُظرت %d اتصالات اليوم<br>many: حُظر %d اتصالًا اليوم<br>other: حُظر %d اتصال اليوم | one: %d bloqué aujourd'hui<br>many: %d bloqués aujourd'hui<br>other: %d bloqués aujourd'hui | one: %d vandaag geblokkeerd<br>other: %d vandaag geblokkeerd |

## Phase 3: Home (`res/values*/strings_ui.xml`, 14 new strings, 1 changed)

- **Changed:** `ui_attention_private_dns` (row in the Phase 2 table above). Phase 2 said
  "Apps can skip CoreLock". That is not what happens: CoreLock drops DNS over TLS, and
  with a Private DNS hostname Android does not fall back, so names stop resolving
  (`dash_private_dns_body`). The new text says websites and apps may not load.
- The protection level names reuse the reviewed 1.0 strings `set_level_minimal`,
  `set_level_standard` and `set_level_strict`; nothing new to check there.
- `ui_whats_new` and its two buttons are hidden until Phase 6 moves Statistics and HTTPS
  inspection (the text would not be true before), but they can be reviewed now.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_home_stopped_sub` | It stopped without you turning it off. Turn it on again. | توقفت الحماية دون أن توقفها أنت. شغّلها مرة أخرى. | Elle s'est arrêtée sans que vous l'ayez désactivée. Réactivez-la. | De bescherming stopte zonder dat je die uitzette. Zet haar weer aan. |
| `ui_home_stopped_other_vpn` | Another VPN app took over. Turn CoreLock on to block again. | تولّى تطبيق VPN آخر الاتصال. شغّل CoreLock ليعود الحظر. | Une autre appli VPN a pris le relais. Réactivez CoreLock pour bloquer à nouveau. | Een andere VPN-app heeft het overgenomen. Zet CoreLock aan om weer te blokkeren. |
| `ui_home_stopped_failed` | CoreLock hit a problem and stopped. Turn it on again. | واجه CoreLock مشكلة فتوقّف. شغّله مرة أخرى. | CoreLock a rencontré un problème et s'est arrêté. Réactivez-le. | CoreLock liep tegen een probleem aan en stopte. Zet het weer aan. |
| `ui_couldnt_load` | Couldn't load | تعذّر التحميل | Chargement impossible | Kon niet laden |
| `ui_attention_usage_access` | Blocking apps in the background needs Usage Access. | حظر التطبيقات في الخلفية يحتاج إلى إذن الوصول إلى بيانات الاستخدام. | Bloquer des applis en arrière-plan nécessite l'accès aux données d'utilisation. | Apps op de achtergrond blokkeren heeft toegang tot gebruiksgegevens nodig. |
| `ui_attention_battery_restricted` | Battery use is restricted, so Android can stop CoreLock. | استخدام البطارية مقيّد، لذلك قد يوقف Android تطبيق CoreLock. | L'utilisation de la batterie est limitée : Android peut arrêter CoreLock. | Batterijgebruik is beperkt, dus Android kan CoreLock stoppen. |
| `ui_attention_battery_optimized` | Some phones stop CoreLock to save battery. Let it run in the background. | بعض الهواتف توقف CoreLock لتوفير البطارية. اسمح له بالعمل في الخلفية. | Certains téléphones arrêtent CoreLock pour économiser la batterie. Autorisez-le à fonctionner en arrière-plan. | Sommige telefoons stoppen CoreLock om batterij te sparen. Laat het op de achtergrond werken. |
| `ui_attention_alert` | New alert: %1$s | تنبيه جديد: %1$s | Nouvelle alerte : %1$s | Nieuwe melding: %1$s |
| `ui_open_alerts` | Open alerts | افتح التنبيهات | Ouvrir les alertes | Meldingen openen |
| `ui_whats_new` | Some things moved. Statistics and HTTPS inspection are now in Settings › Advanced. | تغيّرت أماكن بعض الأشياء. الإحصاءات وفحص HTTPS موجودة الآن في الإعدادات › متقدم. | Certaines choses ont changé de place. Les statistiques et l'inspection HTTPS sont maintenant dans Paramètres › Avancé. | Sommige dingen zijn verplaatst. Statistieken en HTTPS-inspectie staan nu in Instellingen › Geavanceerd. |
| `ui_open_statistics` | Open Statistics | افتح الإحصاءات | Ouvrir les statistiques | Statistieken openen |
| `ui_open_https_inspection` | Open HTTPS inspection | افتح فحص HTTPS | Ouvrir l'inspection HTTPS | HTTPS-inspectie openen |
| `ui_home_security_details` | Security details | تفاصيل الأمان | Détails de sécurité | Beveiligingsdetails |
| `ui_home_security_details_sub` | Alerts, security checks and reports | التنبيهات وفحوص الأمان والتقارير | Alertes, contrôles de sécurité et rapports | Meldingen, beveiligingscontroles en rapporten |

## Phase 3b: fixes after the Phase 3 screenshots (2026-11-03)

- **French `ui_hero_off` shortened:** "La protection est désactivée" → "Protection
  désactivée". At 200 % font the long form needed three lines in the Home hero ("La
  protection / est / désactivée"); the hero allows two (`StatusHeroHeadlineTest`).
  Please check that it reads naturally as a status title.
- **"PrivacyGuard" → "CoreLock"** in the app's own name and in every user-visible string,
  all four languages (the app label is now "CoreLock", the Quick Settings tile "CoreLock
  VPN"). Only the name changed; please check that the sentence around it still reads well,
  especially in Arabic, where "CoreLock" sits next to particles such as لـ and بـ:
  `appd_bg_needs_usage`, `dash_inspection_body`, `dash_inspection_org_body`,
  `dash_private_dns_body`, `diag_battery_optimized`, `diag_battery_restricted`,
  `diag_battery_unrestricted`, `ev_blocked_24h_def`, `ev_ipv6_24h_def`, `lic_app_body`,
  `notif_always_on_turned_off_text`, `notif_inspection_org_text`, `notif_open_for_details`,
  `notif_protection_stopped_text`, `onb_c_https_body`, `onb_c_net_body` (main and
  enterprise), `rule_security_warn_body`, `set_allow_screenshots_desc`,
  `set_always_on_hint`, `set_auto_restart_desc`, `set_auto_restart_dialog_body`,
  `set_updates_hint`, `tls_interception_desc`.
- **Kept "PrivacyGuard" on purpose:** strings that name the HTTPS-inspection certificate,
  which is still called "PrivacyGuard Enterprise CA" in Android's settings
  (`dash_card_decrypt_on_desc`, the old-certificate plurals in `strings_mitm.xml`, the MDM
  description `restriction_ca_install_automatic_desc`), and the `X-PrivacyGuard-Sig` header
  in `restriction_mitm_siem_signing_key_desc`. Renaming the certificate is a change to the
  inspection code and is not part of this step.

## Phase 4: Activity and "Something not working?" (`res/values*/strings_activity.xml`, 66 strings)

The Activity tab (blocked and all connections, the detail sheet with Allow and Block),
the 3-step fix flow, and the texts for pausing protection (used in the next step, D1).

Changed in `strings_common.xml`: `nav_traffic` is now the tab name Activity / النشاط /
Activité / Activiteit (was Traffic).

Points to check in particular:
- `ui_reason_*` are short row labels next to an icon. They must fit on one line at normal
  text size ("Blocked · Skips CoreLock", "Blocked · Never allowed"); longer ones wrap.
- `ui_why_lookup_bypass_port` says this can't be allowed. That is true: those lookups are
  closed in every case. Keep the meaning.
- `ui_times` and `ui_fix_attempts`: the Arabic zero form is never shown (a row always has
  at least one); `few` is for 3 to 10.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_group_just_now` | Just now | الآن | À l'instant | Zojuist |
| `ui_group_today` | Today | اليوم | Aujourd'hui | Vandaag |
| `ui_group_yesterday` | Yesterday | أمس | Hier | Gisteren |
| `ui_group_earlier` | Earlier | في وقت سابق | Plus tôt | Eerder |
| `ui_reason_lookup_bypass` | Blocked · Skips CoreLock | محظور · يتجاوز CoreLock | Bloqué · Contourne CoreLock | Geblokkeerd · Omzeilt CoreLock |
| `ui_reason_lookup_bypass_port` | Blocked · Never allowed | محظور · لا يُسمح به أبدًا | Bloqué · Jamais autorisé | Geblokkeerd · Nooit toegestaan |
| `ui_title_lookup_service` | Private lookup service | خدمة بحث خاصة | Service de recherche privé | Eigen opzoekdienst |
| `ui_reason_setting` | Blocked · A rule or setting | محظور · قاعدة أو إعداد | Bloqué · Une règle ou un réglage | Geblokkeerd · Een regel of instelling |
| `ui_what_named` | %1$s (%2$s) from %3$s | %1$s (%2$s) من %3$s | %1$s (%2$s) depuis %3$s | %1$s (%2$s) van %3$s |
| `ui_what_plain` | %1$s from %2$s | %1$s من %2$s | %1$s depuis %2$s | %1$s van %2$s |
| `ui_why_ads` | It's on the Ads and trackers list. | إنه في قائمة الإعلانات وأدوات التتبع. | Il figure sur la liste pubs et traqueurs. | Het staat op de lijst met advertenties en trackers. |
| `ui_why_malware` | It's on the malware list. Sites like this can steal passwords or install harmful apps. | إنه في قائمة البرامج الضارة. مواقع كهذه قد تسرق كلمات المرور أو تثبّت تطبيقات ضارة. | Il figure sur la liste des logiciels malveillants. Ce genre de site peut voler des mots de passe ou installer des applis dangereuses. | Het staat op de malwarelijst. Zulke sites kunnen wachtwoorden stelen of schadelijke apps installeren. |
| `ui_why_rule` | You blocked it. | أنت حظرته. | Vous l'avez bloqué. | Je hebt het geblokkeerd. |
| `ui_why_rule_named` | You blocked it with your rule “%1$s”. | أنت حظرته بقاعدتك «%1$s». | Vous l'avez bloqué avec votre règle « %1$s ». | Je hebt het geblokkeerd met je regel ‘%1$s’. |
| `ui_why_lookup_bypass` | It's a private lookup service. Apps that use it would skip CoreLock's lists, so it stays blocked. If you run your own, allow it in Rules and allowed sites. | هذه خدمة خاصة للبحث عن عناوين المواقع. التطبيقات التي تستخدمها تتجاوز قوائم CoreLock، لذلك تبقى محظورة. إن كانت لديك خدمتك الخاصة، فاسمح بها في «القواعد والمواقع المسموح بها». | C'est un service de recherche privé. Les applis qui l'utilisent contourneraient les listes de CoreLock, il reste donc bloqué. Si vous avez votre propre service, autorisez-le dans Règles et sites autorisés. | Dit is een eigen opzoekdienst. Apps die hem gebruiken, zouden de lijsten van CoreLock omzeilen, dus hij blijft geblokkeerd. Heb je je eigen dienst, sta hem dan toe bij Regels en toegestane sites. |
| `ui_why_lookup_bypass_port` | The app tried to look up sites in a way that skips CoreLock's lists. This can't be allowed. | حاول التطبيق البحث عن المواقع بطريقة تتجاوز قوائم CoreLock. لا يمكن السماح بذلك. | L'appli a essayé de chercher des sites d'une façon qui contourne les listes de CoreLock. Ce n'est pas possible de l'autoriser. | De app probeerde sites op te zoeken op een manier die de lijsten van CoreLock omzeilt. Dit kan niet worden toegestaan. |
| `ui_why_setting` | A rule or one of your settings stopped it at the time, for example an app block or blocking unencrypted connections. If you removed that rule since, it no longer applies. | أوقفته قاعدة أو أحد إعداداتك في ذلك الوقت، مثل حظر تطبيق أو حظر الاتصالات غير المشفّرة. إذا حذفت تلك القاعدة بعد ذلك، فلم تعد تُطبَّق. | Une règle ou un de vos réglages l’a arrêté à ce moment-là, par exemple le blocage d’une app ou des connexions non chiffrées. Si vous avez supprimé cette règle depuis, elle ne s’applique plus. | Een regel of een van je instellingen heeft het toen tegengehouden, bijvoorbeeld een app-blokkade of het blokkeren van onversleutelde verbindingen. Als je die regel sindsdien hebt verwijderd, geldt hij niet meer. |
| `ui_why_allowed` | Nothing on your lists or rules stopped it. | لم توقفه أي قائمة أو قاعدة. | Aucune liste ni règle ne l'a arrêté. | Geen lijst of regel heeft het tegengehouden. |
| `ui_reason_now_note` | Based on your lists and rules as they are now. | حسب قوائمك وقواعدك الحالية. | D'après vos listes et vos règles actuelles. | Volgens je lijsten en regels zoals ze nu zijn. |
| `ui_allow_anyway` | Allow anyway | اسمح به رغم ذلك | Autoriser quand même | Toch toestaan |
| `ui_block_for_app` | Block for this app | احظره لهذا التطبيق | Bloquer pour cette appli | Blokkeren voor deze app |
| `ui_block_everywhere` | Block everywhere | احظره لكل التطبيقات | Bloquer partout | Overal blokkeren |
| `ui_about_this_app` | About %1$s | عن %1$s | À propos de %1$s | Over %1$s |
| `ui_more_about_name` | More about this name | المزيد عن هذا الاسم | En savoir plus sur ce nom | Meer over deze naam |
| `ui_tech_name` | Name | الاسم | Nom | Naam |
| `ui_tech_ip` | IP address | عنوان IP | Adresse IP | IP-adres |
| `ui_tech_port` | Port | المنفذ | Port | Poort |
| `ui_tech_protocol` | Protocol | البروتوكول | Protocole | Protocol |
| `ui_tech_rule` | Rule | القاعدة | Règle | Regel |
| `ui_tech_time` | Time | الوقت | Heure | Tijd |
| `ui_allowed_try_again` | Allowed. Try the app again. | تم السماح. جرّب التطبيق مرة أخرى. | Autorisé. Réessayez l'appli. | Toegestaan. Probeer de app opnieuw. |
| `ui_undo` | Undo | تراجع | Annuler | Ongedaan maken |
| `ui_blocked_done` | Blocked. | تم الحظر. | Bloqué. | Geblokkeerd. |
| `ui_rule_removed` | Your rule was removed. | حُذفت قاعدتك. | Votre règle a été supprimée. | Je regel is verwijderd. |
| `ui_action_failed` | That didn't work. Try again. | لم ينجح ذلك. حاول مرة أخرى. | Ça n'a pas marché. Réessayez. | Dat is niet gelukt. Probeer het opnieuw. |
| `ui_empty_activity_all` | Nothing here yet. Connections show up here as apps use the internet. | لا شيء هنا بعد. تظهر الاتصالات هنا عندما تستخدم التطبيقات الإنترنت. | Rien pour l'instant. Les connexions s'affichent ici quand les applis utilisent Internet. | Hier staat nog niets. Verbindingen verschijnen hier zodra apps internet gebruiken. |
| `ui_empty_activity_off` | Protection is off, so nothing is being blocked. | الحماية متوقفة، لذلك لا يُحظر أي شيء. | La protection est désactivée, rien n'est donc bloqué. | De bescherming staat uit, dus er wordt niets geblokkeerd. |
| `ui_activity_limit` | Showing the newest %1$d. | يُعرض أحدث %1$d. | Les %1$d plus récents sont affichés. | De nieuwste %1$d worden getoond. |
| `ui_back` | Back | رجوع | Retour | Terug |
| `ui_more_options` | More options | خيارات أخرى | Plus d'options | Meer opties |
| `ui_live_connections` | Live connections | الاتصالات الجارية | Connexions en cours | Huidige verbindingen |
| `rule_allow_domain_for_app` | Allow %1$s for %2$s | السماح بـ %1$s في %2$s | Autoriser %1$s pour %2$s | %1$s toestaan voor %2$s |
| `rule_block_domain_for_app` | Block %1$s for %2$s | حظر %1$s في %2$s | Bloquer %1$s pour %2$s | %1$s blokkeren voor %2$s |
| `ui_fix_step_n` | Step %1$d of %2$d | الخطوة %1$d من %2$d | Étape %1$d sur %2$d | Stap %1$d van %2$d |
| `ui_fix_which_app` | Which app isn't working? | أي تطبيق لا يعمل؟ | Quelle appli ne marche pas ? | Welke app werkt niet? |
| `ui_fix_which_app_sub` | Apps with something blocked in the last hour, newest first. | تطبيقات حُظر لها شيء خلال الساعة الأخيرة، الأحدث أولًا. | Les applis pour lesquelles quelque chose a été bloqué depuis une heure, les plus récentes d'abord. | Apps waarvoor het afgelopen uur iets is geblokkeerd, nieuwste eerst. |
| `ui_fix_not_sure` | Not listed or not sure | غير موجود أو لست متأكدًا | Pas dans la liste ou pas sûr | Staat er niet bij of weet ik niet |
| `ui_fix_not_sure_sub` | See everything blocked in the last hour | اعرض كل ما حُظر خلال الساعة الأخيرة | Voir tout ce qui a été bloqué depuis une heure | Alles zien wat het afgelopen uur is geblokkeerd |
| `ui_fix_blocked_hour` | This was blocked in the last hour | هذا ما حُظر خلال الساعة الأخيرة | Ceci a été bloqué depuis une heure | Dit is het afgelopen uur geblokkeerd |
| `ui_fix_blocked_hour_sub` | The most likely cause is at the top. | السبب الأرجح في الأعلى. | La cause la plus probable est en haut. | De meest waarschijnlijke oorzaak staat bovenaan. |
| `ui_fix_unknown_app_names` | Also blocked, app not known | حُظر أيضًا، والتطبيق غير معروف | Aussi bloqué, appli inconnue | Ook geblokkeerd, app onbekend |
| `ui_fix_none_hour` | Nothing was blocked in the last hour, so CoreLock is probably not the cause. | لم يُحظر شيء خلال الساعة الأخيرة، لذلك على الأرجح ليس CoreLock هو السبب. | Rien n'a été bloqué depuis une heure, CoreLock n'est donc probablement pas en cause. | Het afgelopen uur is niets geblokkeerd, dus CoreLock is waarschijnlijk niet de oorzaak. |
| `ui_fix_none_app` | Nothing was blocked for this app in the last hour. CoreLock is probably not the cause. | لم يُحظر شيء لهذا التطبيق خلال الساعة الأخيرة. على الأرجح ليس CoreLock هو السبب. | Rien n'a été bloqué pour cette appli depuis une heure. CoreLock n'est probablement pas en cause. | Het afgelopen uur is voor deze app niets geblokkeerd. CoreLock is waarschijnlijk niet de oorzaak. |
| `ui_fix_all_handled` | Done. Try the app again. If it still doesn't work, go back and pick another app. | تم. جرّب التطبيق مرة أخرى. إذا لم يعمل بعد، ارجع واختر تطبيقًا آخر. | C'est fait. Réessayez l'appli. Si elle ne marche toujours pas, revenez en arrière et choisissez une autre appli. | Klaar. Probeer de app opnieuw. Werkt hij nog steeds niet, ga dan terug en kies een andere app. |
| `ui_fix_try_pause` | Still not sure? Pause protection for 5 minutes and try the app again. | ما زلت غير متأكد؟ أوقف الحماية مؤقتًا 5 دقائق ثم جرّب التطبيق مرة أخرى. | Toujours pas sûr ? Mettez la protection en pause 5 minutes et réessayez l'appli. | Nog steeds niet zeker? Pauzeer de bescherming 5 minuten en probeer de app opnieuw. |
| `ui_pause_5` | Pause protection for 5 minutes | إيقاف الحماية مؤقتًا 5 دقائق | Mettre la protection en pause 5 minutes | Bescherming 5 minuten pauzeren |
| `ui_pause_needs_alarms` | To pause, CoreLock needs “Alarms &amp; reminders” access, so it can turn itself back on. | للإيقاف المؤقت، يحتاج CoreLock إلى إذن «المنبهات والتذكيرات» ليعود للعمل من تلقاء نفسه. | Pour mettre en pause, CoreLock a besoin de l’accès « Alarmes et rappels », pour se rallumer tout seul. | Om te pauzeren heeft CoreLock toegang tot ‘Wekkers en herinneringen’ nodig, zodat het vanzelf weer aangaat. |
| `ui_pause_always_on` | Pausing is not available while Always-on VPN is on: Android would turn CoreLock back on at once. | الإيقاف المؤقت غير متاح أثناء تشغيل «شبكة VPN الدائمة»: سيعيد Android تشغيل CoreLock فورًا. | La pause n’est pas disponible quand le VPN permanent est activé : Android rallumerait CoreLock aussitôt. | Pauzeren kan niet als ‘Altijd-aan VPN’ aanstaat: Android zet CoreLock dan meteen weer aan. |
| `ui_paused_toast` | Paused for 5 minutes. Try the app now. | أُوقفت الحماية مؤقتًا 5 دقائق. جرّب التطبيق الآن. | En pause pour 5 minutes. Essayez l'appli maintenant. | 5 minuten gepauzeerd. Probeer de app nu. |
| `ui_hero_paused` | Protection is paused | الحماية متوقفة مؤقتًا | La protection est en pause | De bescherming is gepauzeerd |
| `ui_hero_paused_sub` | Back on at %1$s. | تعود في %1$s. | Reprise à %1$s. | Weer aan om %1$s. |
| `ui_resume_now` | Resume now | استأنف الآن | Reprendre maintenant | Nu hervatten |
| `pause_notif_title` | Protection paused | الحماية متوقفة مؤقتًا | Protection en pause | Bescherming gepauzeerd |
| `pause_notif_text` | CoreLock turns back on at %1$s. | يعود CoreLock للعمل في %1$s. | CoreLock se réactive à %1$s. | CoreLock gaat om %1$s weer aan. |
| `ui_times` | one: %d time; other: %d times | zero: لا مرات; one: مرة واحدة; two: مرتان; few: %d مرات; many: %d مرة; other: %d مرة | one: %d fois; many: %d fois; other: %d fois | one: %d keer; other: %d keer |
| `ui_fix_attempts` | one: %d attempt; other: %d attempts | zero: لا محاولات; one: محاولة واحدة; two: محاولتان; few: %d محاولات; many: %d محاولة; other: %d محاولة | one: %d tentative; many: %d tentatives; other: %d tentatives | one: %d poging; other: %d pogingen |

## Phase 5: Apps and App detail (`res/values*/strings_apps_ui.xml`, 39 strings; 1 new and 2 changed elsewhere)

Machine translations; Arabic for the owner, French and Dutch for native speakers.

| Key | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_apps_search` | Search apps | ابحث في التطبيقات | Rechercher des applis | Apps zoeken |
| `ui_apps_active_now` | Active now | نشط الآن | Actives maintenant | Nu actief |
| `ui_sort_by` | Sort by | الترتيب حسب | Trier par | Sorteren op |
| `ui_sort_most_blocked` | Most blocked | الأكثر حظرًا | Plus bloquées | Meest geblokkeerd |
| `ui_sort_name` | Name | الاسم | Nom | Naam |
| `ui_sort_most_data` | Most data | الأكثر بيانات | Plus de données | Meeste data |
| `ui_sort_background` | Background activity | النشاط في الخلفية | Activité en arrière-plan | Activiteit op de achtergrond |
| `ui_sort_risk` | Highest risk | الأعلى خطورة | Risque le plus élevé | Hoogste risico |
| `ui_sort_destinations` | Most destinations | الأكثر وجهات | Plus de destinations | Meeste bestemmingen |
| `ui_apps_empty_search` | No app called “%1$s”. | لا يوجد تطبيق باسم «%1$s». | Aucune appli nommée « %1$s ». | Geen app met de naam ‘%1$s’. |
| `ui_apps_empty_blocked` | No apps blocked. Open an app to block it. | لا توجد تطبيقات محظورة. افتح تطبيقًا لحظره. | Aucune appli bloquée. Ouvrez une appli pour la bloquer. | Geen apps geblokkeerd. Open een app om die te blokkeren. |
| `ui_apps_empty_active` | No app used the internet in the last 5 minutes. | لم يستخدم أي تطبيق الإنترنت في آخر 5 دقائق. | Aucune appli n’a utilisé Internet ces 5 dernières minutes. | Geen app heeft de afgelopen 5 minuten internet gebruikt. |
| `ui_apps_empty` | No apps that use the internet were found. | لم يُعثر على تطبيقات تستخدم الإنترنت. | Aucune appli utilisant Internet n’a été trouvée. | Geen apps gevonden die internet gebruiken. |
| `ui_error_apps` | Couldn’t load your apps. | تعذّر تحميل تطبيقاتك. | Impossible de charger vos applis. | Je apps kunnen niet worden geladen. |
| `ui_show_them` | Show them | اعرضها | Les afficher | Toon ze |
| `ui_clear_search` | Clear search | امسح البحث | Effacer la recherche | Zoekopdracht wissen |
| `ui_risk_word_hint` | Explain | اشرح | Expliquer | Uitleggen |
| `ui_app_status` | Internet access | الوصول إلى الإنترنت | Accès à Internet | Internettoegang |
| `ui_connected_recently` | Connected recently | اتصل مؤخرًا | Connexions récentes | Recent verbonden |
| `ui_connected_recently_sub` | Last 24 hours. Tap a name to allow or block it for this app. | آخر 24 ساعة. المس اسمًا للسماح به أو حظره لهذا التطبيق. | Dernières 24 heures. Touchez un nom pour l’autoriser ou le bloquer pour cette appli. | Afgelopen 24 uur. Tik op een naam om die voor deze app toe te staan of te blokkeren. |
| `ui_other_names` | Other | أخرى | Autres | Overig |
| `ui_built_in_trackers` | Built-in trackers | أدوات تتبّع مدمجة | Traqueurs intégrés | Ingebouwde trackers |
| `ui_built_in_trackers_sub` | Found in the app’s code. CoreLock blocks their connections when they are on its lists. | عُثر عليها في كود التطبيق. يحظر CoreLock اتصالاتها عندما تكون في قوائمه. | Trouvés dans le code de l’appli. CoreLock bloque leurs connexions quand ils figurent sur ses listes. | Gevonden in de code van de app. CoreLock blokkeert hun verbindingen als ze op de lijsten staan. |
| `ui_no_trackers_found` | No built-in trackers found. | لم يُعثر على أدوات تتبّع مدمجة. | Aucun traqueur intégré trouvé. | Geen ingebouwde trackers gevonden. |
| `ui_checking_app` | Checking the app’s code… | جارٍ فحص كود التطبيق… | Vérification du code de l’appli… | De code van de app wordt gecontroleerd… |
| `ui_empty_app_connections` | No connections in the last 24 hours. | لا اتصالات في آخر 24 ساعة. | Aucune connexion ces dernières 24 heures. | Geen verbindingen in de afgelopen 24 uur. |
| `ui_error_app` | Couldn’t load this app’s connections. | تعذّر تحميل اتصالات هذا التطبيق. | Impossible de charger les connexions de cette appli. | De verbindingen van deze app kunnen niet worden geladen. |
| `ui_usage_access_needed` | CoreLock needs Usage Access to know when an app is in the background. | يحتاج CoreLock إلى إذن الوصول إلى بيانات الاستخدام ليعرف متى يعمل التطبيق في الخلفية. | CoreLock a besoin de l’accès aux données d’utilisation pour savoir quand une appli est en arrière-plan. | CoreLock heeft toegang tot gebruiksgegevens nodig om te weten wanneer een app op de achtergrond is. |
| `ui_app_now_allowed` | %1$s is allowed. | %1$s مسموح الآن. | %1$s est autorisée. | %1$s is toegestaan. |
| `ui_app_now_blocked` | %1$s is blocked. | %1$s محظور الآن. | %1$s est bloquée. | %1$s is geblokkeerd. |
| `ui_app_now_blocked_background` | %1$s is blocked in the background. | %1$s محظور الآن في الخلفية. | %1$s est bloquée en arrière-plan. | %1$s is op de achtergrond geblokkeerd. |
| `ui_share_report` | Share report | مشاركة التقرير | Partager le rapport | Rapport delen |
| `ui_tech_risk_grade` | Risk grade | درجة الخطورة | Note de risque | Risicocijfer |
| `ui_tech_permissions` | Permissions | الأذونات | Autorisations | Rechten |
| `ui_tech_behaviour` | Behaviour, last 24 hours | السلوك، آخر 24 ساعة | Comportement, dernières 24 heures | Gedrag, afgelopen 24 uur |
| `ui_tech_inspected` | Inspected content | المحتوى المفحوص | Contenu inspecté | Geïnspecteerde inhoud |
| `ui_tech_names` | Every name | كل الأسماء | Tous les noms | Alle namen |
| `ui_apps_could_watch` | one: %d app could be watching you. Check that you installed it yourself.; other: %d apps could be watching you. Check that you installed them yourself. | zero: لا توجد تطبيقات قد تراقبك.; one: تطبيق واحد قد يراقبك. تأكد أنك ثبّته بنفسك.; two: تطبيقان قد يراقبانك. تأكد أنك ثبّتهما بنفسك.; few: %d تطبيقات قد تراقبك. تأكد أنك ثبّتها بنفسك.; many: %d تطبيقًا قد يراقبك. تأكد أنك ثبّتها بنفسك.; other: %d تطبيق قد يراقبك. تأكد أنك ثبّتها بنفسك. | one: %d appli pourrait vous surveiller. Vérifiez que vous l’avez installée vous-même.; many: %d applis pourraient vous surveiller. Vérifiez que vous les avez installées vous-même.; other: %d applis pourraient vous surveiller. Vérifiez que vous les avez installées vous-même. | one: %d app kan je misschien volgen. Controleer of je die zelf hebt geïnstalleerd.; other: %d apps kunnen je misschien volgen. Controleer of je die zelf hebt geïnstalleerd. |
| `ui_tech_permissions_n` | one: %d permission; other: %d permissions | zero: لا أذونات; one: إذن واحد; two: إذنان; few: %d أذونات; many: %d إذنًا; other: %d إذن | one: %d autorisation; many: %d autorisations; other: %d autorisations | one: %d recht; other: %d rechten |
| `trf_per_day_under_one` | less than once a day | أقل من مرة واحدة يوميًا | moins d’une fois par jour | minder dan één keer per dag |

Changed in Phase 5 (rows updated above in Phase 4): `ui_reason_setting`, `ui_why_setting`.

## Phase 6: Settings (`res/values*/strings_settings_ui.xml`, 55 strings)

The new Settings list, Blocklists, Kill switch, Theme, Data and About. Links (`url_*`) are not
translated and are left out.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_settings` | Settings | الإعدادات | Réglages | Instellingen |
| `ui_set_rules` | Rules and allowed sites | القواعد والمواقع المسموح بها | Règles et sites autorisés | Regels en toegestane sites |
| `ui_set_rules_sub` | Sites and apps you allowed or blocked | المواقع والتطبيقات التي سمحت بها أو حظرتها | Sites et applis que vous avez autorisés ou bloqués | Sites en apps die je hebt toegestaan of geblokkeerd |
| `ui_set_blocklists` | Blocklists | قوائم الحظر | Listes de blocage | Blokkeerlijsten |
| `ui_set_lists_updated` | Updated %1$s | حُدّثت %1$s | Mises à jour %1$s | Bijgewerkt %1$s |
| `ui_set_lists_some_missing` | Updated %1$s. Some lists haven’t downloaded yet. | حُدّثت %1$s. بعض القوائم لم تُنزَّل بعد. | Mises à jour %1$s. Certaines listes ne sont pas encore téléchargées. | Bijgewerkt %1$s. Sommige lijsten zijn nog niet gedownload. |
| `ui_set_lists_never` | Not downloaded yet. Built-in lists are used. | لم تُنزَّل بعد. تُستخدم القوائم المدمجة. | Pas encore téléchargées. Les listes intégrées sont utilisées. | Nog niet gedownload. De ingebouwde lijsten worden gebruikt. |
| `ui_set_kill_switch` | Kill switch | مفتاح الإيقاف | Coupure d’urgence | Noodstop |
| `ui_set_ks_on` | On: no internet without CoreLock | مفعّل: لا إنترنت بدون CoreLock | Activée : pas d’internet sans CoreLock | Aan: geen internet zonder CoreLock |
| `ui_set_ks_off` | Off | متوقف | Désactivée | Uit |
| `ui_set_ks_not_running` | Turn protection on to check | شغّل الحماية للتحقق | Activez la protection pour vérifier | Zet de bescherming aan om dit te controleren |
| `ui_set_ks_unsupported` | Android can’t report this before Android 10 | لا يستطيع Android معرفة ذلك قبل Android 10 | Android ne peut pas l’indiquer avant Android 10 | Android kan dit niet melden vóór Android 10 |
| `ui_set_notifications` | Notifications | الإشعارات | Notifications | Meldingen |
| `ui_set_notifications_sub` | Status and important warnings | الحالة والتحذيرات المهمة | État et avertissements importants | Status en belangrijke waarschuwingen |
| `ui_set_show_details` | Show details | عرض التفاصيل | Afficher les détails | Details tonen |
| `ui_set_show_details_sub` | Show app names in notifications | عرض أسماء التطبيقات في الإشعارات | Afficher le nom des applis dans les notifications | Appnamen in meldingen tonen |
| `ui_set_weekly` | Weekly summary | الملخص الأسبوعي | Résumé hebdomadaire | Wekelijks overzicht |
| `ui_set_weekly_sub` | A short report every week | تقرير قصير كل أسبوع | Un court rapport chaque semaine | Elke week een kort rapport |
| `ui_set_language` | Language | اللغة | Langue | Taal |
| `ui_set_appearance` | Appearance | المظهر | Apparence | Weergave |
| `ui_set_theme` | Theme | السمة | Thème | Thema |
| `ui_theme_system` | System | النظام | Système | Systeem |
| `ui_theme_light` | Light | فاتح | Clair | Licht |
| `ui_theme_dark` | Dark | داكن | Sombre | Donker |
| `ui_set_data` | Data | البيانات | Données | Gegevens |
| `ui_set_keep_history` | Keep history for | الاحتفاظ بالسجل لمدة | Conserver l’historique | Geschiedenis bewaren |
| `ui_set_keep_history_sub` | Older history is deleted automatically. | يُحذف السجل الأقدم تلقائيًا. | L’historique plus ancien est supprimé automatiquement. | Oudere geschiedenis wordt automatisch verwijderd. |
| `ui_set_export` | Export history | تصدير السجل | Exporter l’historique | Geschiedenis exporteren |
| `ui_set_export_sub` | Save a file you can open in a spreadsheet | احفظ ملفًا يمكنك فتحه في جدول بيانات | Enregistrer un fichier lisible dans un tableur | Een bestand opslaan dat je in een spreadsheet kunt openen |
| `ui_set_export_title` | Export history? | تصدير السجل؟ | Exporter l’historique ? | Geschiedenis exporteren? |
| `ui_set_export_body` | The file lists which sites your apps connected to in the last 7 days. Anyone you share it with can read it. Keep it private. | يحتوي الملف على المواقع التي اتصلت بها تطبيقاتك خلال آخر 7 أيام. يستطيع قراءته كل من تشاركه معه. احتفظ به لنفسك. | Le fichier indique les sites auxquels vos applis se sont connectées ces 7 derniers jours. Toute personne à qui vous l’envoyez peut le lire. Gardez-le pour vous. | Het bestand toont met welke sites je apps de afgelopen 7 dagen verbinding maakten. Iedereen met wie je het deelt, kan het lezen. Houd het privé. |
| `ui_set_export_action` | Export | تصدير | Exporter | Exporteren |
| `ui_set_export_failed` | Couldn’t create the file. Try again. | تعذّر إنشاء الملف. حاول مرة أخرى. | Impossible de créer le fichier. Réessayez. | Het bestand kon niet worden gemaakt. Probeer het opnieuw. |
| `ui_set_screenshots` | Allow screenshots | السماح بلقطات الشاشة | Autoriser les captures d’écran | Screenshots toestaan |
| `ui_set_screenshots_sub` | Off protects your history | إيقافه يحمي سجلك | Désactivé, votre historique est protégé | Uit beschermt je geschiedenis |
| `ui_set_clear_sub` | Deletes history and statistics. Settings, rules and blocklists stay. | يحذف السجل والإحصاءات. تبقى الإعدادات والقواعد وقوائم الحظر. | Supprime l’historique et les statistiques. Les réglages, les règles et les listes restent. | Verwijdert geschiedenis en statistieken. Instellingen, regels en lijsten blijven. |
| `ui_set_cleared` | History and statistics deleted. | حُذف السجل والإحصاءات. | Historique et statistiques supprimés. | Geschiedenis en statistieken verwijderd. |
| `ui_set_about` | About | حول | À propos | Over |
| `ui_set_version` | Version | الإصدار | Version | Versie |
| `ui_set_policy` | Blocking policy | سياسة الحظر | Politique de blocage | Blokkeerbeleid |
| `ui_set_policy_sub` | What each level blocks, and why | ما يحظره كل مستوى، ولماذا | Ce que bloque chaque niveau, et pourquoi | Wat elk niveau blokkeert, en waarom |
| `ui_set_licenses` | Open-source licenses | تراخيص المصادر المفتوحة | Licences open source | Opensourcelicenties |
| `ui_set_new_versions` | See new versions | عرض الإصدارات الجديدة | Voir les nouvelles versions | Nieuwe versies bekijken |
| `ui_set_new_versions_sub` | Opens the GitHub releases page | يفتح صفحة الإصدارات على GitHub | Ouvre la page des versions sur GitHub | Opent de releasepagina op GitHub |
| `ui_set_report` | Report a problem | الإبلاغ عن مشكلة | Signaler un problème | Een probleem melden |
| `ui_set_report_sub` | Opens GitHub in your browser | يفتح GitHub في متصفحك | Ouvre GitHub dans votre navigateur | Opent GitHub in je browser |
| `ui_set_no_browser` | No app can open this link. | لا يوجد تطبيق يستطيع فتح هذا الرابط. | Aucune appli ne peut ouvrir ce lien. | Geen enkele app kan deze link openen. |
| `ui_ks_body` | Only Android can block the internet when CoreLock isn’t running. Turn on “Always-on VPN” and “Block connections without VPN” for CoreLock in Android’s VPN settings. | وحده Android يستطيع حظر الإنترنت عندما لا يعمل CoreLock. فعّل «شبكة VPN قيد التشغيل دائمًا» و«حظر الاتصالات بدون VPN» لـ CoreLock في إعدادات VPN في Android. | Seul Android peut bloquer internet quand CoreLock ne fonctionne pas. Activez « VPN permanent » et « Bloquer les connexions sans VPN » pour CoreLock dans les réglages VPN d’Android. | Alleen Android kan internet blokkeren als CoreLock niet draait. Zet ‘Altijd-aan VPN’ en ‘Verbindingen zonder VPN blokkeren’ aan voor CoreLock in de VPN-instellingen van Android. |
| `ui_ks_tradeoff` | With both on, nothing goes out unprotected. If CoreLock stops, the phone stays offline until CoreLock starts again. | عند تفعيل الخيارين لا يخرج شيء دون حماية. وإذا توقف CoreLock يبقى الهاتف بلا إنترنت حتى يعمل CoreLock من جديد. | Avec les deux activés, rien ne sort sans protection. Si CoreLock s’arrête, le téléphone reste hors ligne jusqu’au redémarrage de CoreLock. | Met beide aan gaat er niets onbeschermd naar buiten. Als CoreLock stopt, blijft de telefoon offline tot CoreLock weer start. |
| `ui_ks_open` | Open Android settings | فتح إعدادات Android | Ouvrir les réglages d’Android | Android-instellingen openen |
| `ui_lists_names` | Names blocked | الأسماء المحظورة | Noms bloqués | Geblokkeerde namen |
| `ui_lists_refresh` | Refresh now | التحديث الآن | Actualiser maintenant | Nu vernieuwen |
| `ui_lists_refreshing` | Downloading the newest lists… | جارٍ تنزيل أحدث القوائم… | Téléchargement des listes les plus récentes… | De nieuwste lijsten worden gedownload… |
| `ui_lists_auto` | CoreLock updates the lists by itself every week on Wi-Fi. | يحدّث CoreLock القوائم بنفسه كل أسبوع عبر Wi-Fi. | CoreLock met les listes à jour tout seul chaque semaine en Wi-Fi. | CoreLock werkt de lijsten elke week zelf bij via wifi. |
| `ui_lists_details` | Details for each list are in Advanced › Diagnostics. | تفاصيل كل قائمة في «متقدم › التشخيص». | Les détails de chaque liste sont dans Avancé › Diagnostic. | Details per lijst staan in Geavanceerd › Diagnose. |


### Rules and allowed sites (`res/values*/strings_rules_ui.xml`, 32 strings)

The restyled Rules screen and its add sheet. French uses "appli", as the other 1.1 screens.
The empty state names the Activity tab and the hint names the Apps tab: check they match the
tab names (`nav_traffic`, `nav_apps`). Reused from 1.0 without change: `rules_suggested`,
`rules_skip`, `rules_add_button`, `rules_triggered`, `rules_domain_exact`, `rules_ip_invalid`.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_rules_add` | Add rule | إضافة قاعدة | Ajouter une règle | Regel toevoegen |
| `ui_rules_empty` | No rules yet. Allow or block a site from Activity, or add a rule here. | لا توجد قواعد بعد. اسمح بموقع أو احظره من النشاط، أو أضف قاعدة هنا. | Aucune règle pour l’instant. Autorisez ou bloquez un site depuis Activité, ou ajoutez une règle ici. | Nog geen regels. Sta een site toe of blokkeer hem vanuit Activiteit, of voeg hier een regel toe. |
| `ui_rules_error` | Your rules couldn’t be loaded. | تعذّر تحميل قواعدك. | Impossible de charger vos règles. | Je regels konden niet worden geladen. |
| `ui_rules_save_failed` | The change couldn’t be saved. Try again. | تعذّر حفظ التغيير. حاول مرة أخرى. | La modification n’a pas pu être enregistrée. Réessayez. | De wijziging kon niet worden opgeslagen. Probeer het opnieuw. |
| `ui_rules_added` | Rule added | أُضيفت القاعدة | Règle ajoutée | Regel toegevoegd |
| `ui_rules_deleted` | Rule deleted | حُذفت القاعدة | Règle supprimée | Regel verwijderd |
| `ui_rules_search` | Search sites, apps or addresses | ابحث عن مواقع أو تطبيقات أو عناوين | Rechercher des sites, applis ou adresses | Zoek sites, apps of adressen |
| `ui_rules_no_match` | No rule matches “%1$s”. | لا توجد قاعدة تطابق «%1$s». | Aucune règle ne correspond à « %1$s ». | Geen regel past bij ‘%1$s’. |
| `ui_rules_effect_block` | Blocks | تحظر | Bloque | Blokkeert |
| `ui_rules_effect_block_bg` | Blocks in the background | تحظر في الخلفية | Bloque en arrière-plan | Blokkeert op de achtergrond |
| `ui_rules_effect_allow` | Allows | تسمح | Autorise | Staat toe |
| `ui_rules_effect_allow_risky` | Allows, despite the malware warning | تسمح رغم تحذير البرمجيات الخبيثة | Autorise, malgré l’alerte logiciel malveillant | Staat toe, ondanks de malwarewaarschuwing |
| `ui_rules_off` | Off | متوقفة | Désactivée | Uit |
| `ui_rules_d_site` | Site: %1$s | الموقع: %1$s | Site : %1$s | Site: %1$s |
| `ui_rules_d_app` | App: %1$s | التطبيق: %1$s | Appli : %1$s | App: %1$s |
| `ui_rules_d_address` | Address: %1$s | العنوان: %1$s | Adresse : %1$s | Adres: %1$s |
| `ui_rules_d_port` | Port %1$d | المنفذ %1$d | Port %1$d | Poort %1$d |
| `ui_rules_d_unencrypted` | Unencrypted connections only | الاتصالات غير المشفّرة فقط | Connexions non chiffrées uniquement | Alleen onversleutelde verbindingen |
| `ui_rules_use` | Use rule: %1$s | استخدام القاعدة: %1$s | Utiliser la règle : %1$s | Regel gebruiken: %1$s |
| `ui_rules_delete` | Delete rule: %1$s | حذف القاعدة: %1$s | Supprimer la règle : %1$s | Regel verwijderen: %1$s |
| `ui_rules_kind_site` | Site | موقع | Site | Site |
| `ui_rules_kind_app` | App | تطبيق | Appli | App |
| `ui_rules_kind_address` | Address | عنوان | Adresse | Adres |
| `ui_rules_site_label` | Site name, like example.com | اسم الموقع، مثل example.com | Nom du site, comme example.com | Sitenaam, zoals example.com |
| `ui_rules_site_invalid` | That isn’t a site name. Write it like example.com. | هذا ليس اسم موقع. اكتبه مثل example.com. | Ce n’est pas un nom de site. Écrivez-le comme example.com. | Dat is geen sitenaam. Schrijf hem zoals example.com. |
| `ui_rules_app_label` | App package name | اسم حزمة التطبيق | Nom du paquet de l’appli | Pakketnaam van de app |
| `ui_rules_app_hint` | Like com.example.app. To block an installed app, it is easier to open it in Apps. | مثل com.example.app. لحظر تطبيق مثبّت، الأسهل أن تفتحه في التطبيقات. | Comme com.example.app. Pour bloquer une appli installée, il est plus simple de l’ouvrir dans Apps. | Zoals com.example.app. Een geïnstalleerde app blokkeer je makkelijker door hem in Apps te openen. |
| `ui_rules_app_invalid` | That isn’t a package name. It looks like com.example.app. | هذا ليس اسم حزمة. يبدو مثل com.example.app. | Ce n’est pas un nom de paquet. Il ressemble à com.example.app. | Dat is geen pakketnaam. Die ziet eruit als com.example.app. |
| `ui_rules_address_label` | IPv4 address or range | عنوان IPv4 أو نطاق عناوين | Adresse IPv4 ou plage | IPv4-adres of -bereik |
| `ui_rules_address_hint` | Like 1.2.3.4 or 10.0.0.0/8. | مثل 1.2.3.4 أو 10.0.0.0/8. | Comme 1.2.3.4 ou 10.0.0.0/8. | Zoals 1.2.3.4 of 10.0.0.0/8. |
| `ui_rules_allow_note` | Allow wins over the ads and trackers lists. For a site on a malware list, CoreLock warns you first. | السماح يتقدّم على قوائم الإعلانات والمتتبّعات. وإذا كان الموقع في قائمة برمجيات خبيثة، ينبّهك CoreLock أولًا. | Autoriser l’emporte sur les listes de publicités et de traqueurs. Pour un site sur une liste de logiciels malveillants, CoreLock vous avertit d’abord. | Toestaan gaat voor de lijsten met advertenties en trackers. Voor een site op een malwarelijst waarschuwt CoreLock je eerst. |
| `ui_rules_yours` | one: Your rules · %1$d; other: Your rules · %1$d | zero: قواعدك · %1$d; one: قواعدك · %1$d; two: قواعدك · %1$d; few: قواعدك · %1$d; many: قواعدك · %1$d; other: قواعدك · %1$d | one: Vos règles · %1$d; many: Vos règles · %1$d; other: Vos règles · %1$d | one: Je regels · %1$d; other: Je regels · %1$d |

## Phase 7a: Advanced (`res/values*/strings_advanced_ui.xml`, 70 strings)

Advanced, Network, Use two proxy servers, Analysis and Diagnostics. Titles follow DESIGN_SPEC_1.1.md §7.
List names (HaGeZi, OISD) and server names (Cloudflare, Google, Quad9) are proper names and are
not translated. Reused from 1.0 without change: `set_dual_desc`, `set_dual_flow`, `set_host`,
`set_port`, `set_username_opt`, `set_password_opt`, the `set_status_*` words, the App integrity
rows (`set_runtime_posture` … `set_keystore_sw`), `diag_battery_*`, `set_refresh_now`.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_adv_network` | Network | الشبكة | Réseau | Netwerk |
| `ui_adv_network_sub` | Encrypted DNS, DNS server, proxies and blocking options | DNS المشفّر، وخادم DNS، والخوادم الوسيطة، وخيارات الحظر | DNS chiffré, serveur DNS, proxys et options de blocage | Versleutelde DNS, DNS-server, proxy’s en blokkeeropties |
| `ui_adv_https` | HTTPS inspection | فحص HTTPS | Inspection HTTPS | HTTPS-inspectie |
| `ui_adv_https_sub` | See inside some encrypted connections | رؤية محتوى بعض الاتصالات المشفّرة | Voir le contenu de certaines connexions chiffrées | In sommige versleutelde verbindingen kijken |
| `ui_adv_analysis` | Analysis | التحليل | Analyse | Analyse |
| `ui_adv_analysis_sub` | Statistics, alerts and reports | الإحصاءات والتنبيهات والتقارير | Statistiques, alertes et rapports | Statistieken, meldingen en rapporten |
| `ui_adv_diagnostics` | Diagnostics | التشخيص | Diagnostic | Diagnose |
| `ui_adv_diagnostics_sub` | Network checks, app integrity and blocklist details | فحوص الشبكة، وسلامة التطبيق، وتفاصيل قوائم الحظر | Vérifications réseau, intégrité de l’appli et détails des listes de blocage | Netwerkcontroles, app-integriteit en details van blokkeerlijsten |
| `ui_net_group_lookups` | Lookups | البحث عن العناوين | Recherches de noms | Naamopzoekingen |
| `ui_net_group_blocking` | Blocking | الحظر | Blocage | Blokkeren |
| `ui_net_group_more` | More | المزيد | Plus | Meer |
| `ui_net_doh` | Encrypted DNS | DNS المشفّر | DNS chiffré | Versleutelde DNS |
| `ui_net_doh_sub` | Hide which sites you look up from your network | أخفِ عن شبكتك المواقع التي تبحث عنها | Masquer à votre réseau les sites que vous recherchez | Verberg voor je netwerk welke sites je opzoekt |
| `ui_net_doh_note` | Lookups go to %1$s, encrypted. If it can’t be reached (some hotel or work Wi-Fi blocks it), sites stop loading until you turn this off. | يُرسل البحث إلى %1$s مشفّرًا. إذا تعذّر الوصول إليه (بعض شبكات Wi-Fi في الفنادق أو العمل تحظره)، تتوقف المواقع عن التحميل حتى توقف هذا الخيار. | Les recherches vont à %1$s, chiffrées. S’il est injoignable (certains Wi-Fi d’hôtel ou de bureau le bloquent), les sites ne se chargent plus tant que vous ne désactivez pas cette option. | Opzoekingen gaan versleuteld naar %1$s. Als die niet bereikbaar is (sommige wifi in hotels of op het werk blokkeert hem), laden sites niet meer tot je dit uitzet. |
| `ui_net_dns` | DNS server | خادم DNS | Serveur DNS | DNS-server |
| `ui_net_dns_sub` | Which server answers lookups | الخادم الذي يجيب عن البحث | Le serveur qui répond aux recherches | Welke server opzoekingen beantwoordt |
| `ui_net_dns_other` | Other server | خادم آخر | Autre serveur | Andere server |
| `ui_net_dns_field` | Server address, like 1.1.1.1 | عنوان الخادم، مثل 1.1.1.1 | Adresse du serveur, comme 1.1.1.1 | Serveradres, zoals 1.1.1.1 |
| `ui_net_dns_invalid` | Enter an IP address, like 1.1.1.1 or 2606:4700::1111. | أدخل عنوان IP، مثل 1.1.1.1 أو 2606:4700::1111. | Saisissez une adresse IP, comme 1.1.1.1 ou 2606:4700::1111. | Voer een IP-adres in, zoals 1.1.1.1 of 2606:4700::1111. |
| `ui_net_dns_use` | Use this server | استخدم هذا الخادم | Utiliser ce serveur | Deze server gebruiken |
| `ui_net_dns_doh_note` | With Encrypted DNS on, picking Cloudflare, Google or Quad9 also picks it for encrypted lookups. | عند تشغيل DNS المشفّر، يؤدي اختيار Cloudflare أو Google أو Quad9 إلى اختياره للبحث المشفّر أيضًا. | Avec le DNS chiffré activé, choisir Cloudflare, Google ou Quad9 le choisit aussi pour les recherches chiffrées. | Met versleutelde DNS aan kies je met Cloudflare, Google of Quad9 ook de server voor versleutelde opzoekingen. |
| `ui_net_quic` | Fast web protocol (QUIC) | بروتوكول الويب السريع (QUIC) | Protocole web rapide (QUIC) | Snel webprotocol (QUIC) |
| `ui_net_quic_sub` | Block it so CoreLock can see more connections | احظره ليرى CoreLock مزيدًا من الاتصالات | Le bloquer pour que CoreLock voie plus de connexions | Blokkeer het zodat CoreLock meer verbindingen ziet |
| `ui_net_weak_tls` | Old encryption | التشفير القديم | Ancien chiffrement | Oude versleuteling |
| `ui_net_weak_tls_sub` | Block connections with outdated security | احظر الاتصالات ذات الحماية القديمة | Bloquer les connexions à la sécurité dépassée | Blokkeer verbindingen met verouderde beveiliging |
| `ui_net_cleartext` | Unencrypted connections | الاتصالات غير المشفّرة | Connexions non chiffrées | Onversleutelde verbindingen |
| `ui_net_cleartext_sub` | Block connections anyone on the network can read | احظر الاتصالات التي يستطيع أي شخص على الشبكة قراءتها | Bloquer les connexions que tout le monde sur le réseau peut lire | Blokkeer verbindingen die iedereen op het netwerk kan lezen |
| `ui_net_public_ip` | Public IP check | فحص عنوان IP العام | Vérification de l’IP publique | Controle van openbaar IP |
| `ui_net_public_ip_sub` | Ask a server for your public address | اسأل خادمًا عن عنوانك العام | Demander votre adresse publique à un serveur | Vraag een server naar je openbare adres |
| `ui_net_proxy` | Use two proxy servers | استخدام خادمين وسيطين | Utiliser deux serveurs proxy | Twee proxyservers gebruiken |
| `ui_net_proxy_sub` | Not encrypted. The first server can see where you connect. | غير مشفّر. يستطيع الخادم الأول رؤية الوجهات التي تتصل بها. | Non chiffré. Le premier serveur voit où vous vous connectez. | Niet versleuteld. De eerste server ziet waarmee je verbinding maakt. |
| `ui_on` | On | مفعّل | Activé | Aan |
| `ui_off` | Off | متوقف | Désactivé | Uit |
| `ui_proxy_first` | First server | الخادم الأول | Premier serveur | Eerste server |
| `ui_proxy_second` | Second server | الخادم الثاني | Second serveur | Tweede server |
| `ui_proxy_host_invalid` | Enter a server name or address, without http://. | أدخل اسم خادم أو عنوانه، بدون http://. | Saisissez un nom ou une adresse de serveur, sans http://. | Voer een servernaam of -adres in, zonder http://. |
| `ui_proxy_port_invalid` | Enter a port from 1 to 65535. | أدخل منفذًا من 1 إلى 65535. | Saisissez un port de 1 à 65535. | Voer een poort van 1 tot 65535 in. |
| `ui_proxy_incomplete` | Fill in both servers for the chain to work. | املأ بيانات الخادمين كي تعمل السلسلة. | Remplissez les deux serveurs pour que la chaîne fonctionne. | Vul beide servers in, anders werkt de keten niet. |
| `ui_proxy_block` | Block other traffic | احظر باقي حركة البيانات | Bloquer le reste du trafic | Ander verkeer blokkeren |
| `ui_proxy_block_sub` | Stop traffic that can’t go through the servers | أوقف حركة البيانات التي لا يمكنها المرور عبر الخادمين | Arrêter le trafic qui ne peut pas passer par les serveurs | Stop verkeer dat niet via de servers kan |
| `ui_an_security` | Security analysis | التحليل الأمني | Analyse de sécurité | Beveiligingsanalyse |
| `ui_an_stats` | Statistics | الإحصاءات | Statistiques | Statistieken |
| `ui_an_stats_sub` | Privacy score, data use, IT report and packet capture | درجة الخصوصية، واستهلاك البيانات، وتقرير تقنية المعلومات، والتقاط الحزم | Score de confidentialité, données utilisées, rapport informatique et capture de paquets | Privacyscore, dataverbruik, IT-rapport en pakketopname |
| `ui_an_alerts` | Alerts | التنبيهات | Alertes | Meldingen |
| `ui_an_alerts_sub` | Warnings CoreLock raised | التحذيرات التي أطلقها CoreLock | Les avertissements émis par CoreLock | Waarschuwingen van CoreLock |
| `ui_an_systems` | Security systems | أنظمة الأمان | Systèmes de sécurité | Beveiligingssystemen |
| `ui_an_systems_sub` | Checks that watch for unusual behaviour, and the night report | فحوص تراقب السلوك غير المعتاد، وتقرير الليل | Des vérifications qui repèrent les comportements inhabituels, et le rapport de nuit | Controles die letten op ongewoon gedrag, en het nachtrapport |
| `ui_an_ad_privacy` | Ad privacy | خصوصية الإعلانات | Confidentialité publicitaire | Advertentieprivacy |
| `ui_an_ads` | Ads and trackers detail | تفاصيل الإعلانات والمتتبّعات | Détail des publicités et traqueurs | Details van advertenties en trackers |
| `ui_an_ct` | Certificate checks | فحص الشهادات | Vérification des certificats | Certificaatcontroles |
| `ui_an_ct_sub` | Warn about suspicious site certificates | نبّهني إلى شهادات المواقع المريبة | Avertir en cas de certificats de site suspects | Waarschuw bij verdachte sitecertificaten |
| `ui_an_report` | Make a report now | أنشئ تقريرًا الآن | Créer un rapport maintenant | Nu een rapport maken |
| `ui_an_report_sub` | The weekly summary, now, as a notification | الملخص الأسبوعي، الآن، كإشعار | Le résumé hebdomadaire, maintenant, en notification | De weekelijkse samenvatting, nu, als melding |
| `ui_an_report_started` | Making the report. It arrives as a notification. | يجري إعداد التقرير. سيصلك كإشعار. | Rapport en cours. Il arrivera en notification. | Rapport wordt gemaakt. Het komt als melding. |
| `ui_diag_network` | Network diagnostics | تشخيص الشبكة | Diagnostic réseau | Netwerkdiagnose |
| `ui_diag_network_sub` | Check what may stop protection | افحص ما قد يوقف الحماية | Vérifier ce qui pourrait arrêter la protection | Controleer wat de bescherming kan stoppen |
| `ui_diag_run` | Run checks | ابدأ الفحص | Lancer les vérifications | Controles uitvoeren |
| `ui_diag_running` | Checking… | جارٍ الفحص… | Vérification… | Bezig met controleren… |
| `ui_diag_not_run` | Not checked yet. | لم يُفحص بعد. | Pas encore vérifié. | Nog niet gecontroleerd. |
| `ui_diag_integrity` | App integrity | سلامة التطبيق | Intégrité de l’appli | App-integriteit |
| `ui_diag_integrity_sub` | Check that CoreLock hasn’t been changed | تحقّق من أن CoreLock لم يُعدَّل | Vérifier que CoreLock n’a pas été modifiée | Controleer of CoreLock niet is gewijzigd |
| `ui_diag_lists` | Blocklist details | تفاصيل قوائم الحظر | Détails des listes de blocage | Details van blokkeerlijsten |
| `ui_diag_list_updated` | Downloaded %1$s | نُزّلت %1$s | Téléchargée %1$s | Gedownload %1$s |
| `ui_diag_list_never` | Not downloaded yet | لم تُنزَّل بعد | Pas encore téléchargée | Nog niet gedownload |
| `ui_diag_names` | Names per list | عدد الأسماء في كل قائمة | Noms par liste | Namen per lijst |
| `ui_diag_battery` | Battery | البطارية | Batterie | Batterij |
| `ui_diag_battery_ok` | Unrestricted: Android won’t stop protection to save battery. | غير مقيّد: لن يوقف Android الحماية لتوفير البطارية. | Sans restriction : Android n’arrêtera pas la protection pour économiser la batterie. | Onbeperkt: Android stopt de bescherming niet om batterij te sparen. |
| `ui_diag_builtin` | Built-in: %1$s | مدمجة: %1$s | Intégrée : %1$s | Ingebouwd: %1$s |
| `ui_diag_list_count` | %1$s: %2$s | %1$s: %2$s | %1$s : %2$s | %1$s: %2$s |
| `ui_diag_names_n` | one: %1$s name; other: %1$s names | zero: %1$s اسم; one: اسم واحد; two: اسمان; few: %1$s أسماء; many: %1$s اسمًا; other: %1$s اسم | one: %1$s nom; many: %1$s noms; other: %1$s noms | one: %1$s naam; other: %1$s namen |

## Phase 7b: HTTPS inspection (`app/src/enterprise/res/values*/strings_https_ui.xml`, 4 strings)

The D5 confirmation shown every time HTTPS inspection is switched on, and the label TalkBack
reads for the certificate button. Everything else on the screen reuses the 1.0 `mitm_*`
strings unchanged; the consent dialog text is deliberately not changed. Arabic, French and
Dutch are machine-made and need review.

| String | English | Arabic | French | Dutch |
|---|---|---|---|---|
| `ui_https_confirm_title` | Turn on HTTPS inspection? | تشغيل فحص HTTPS؟ | Activer l’inspection HTTPS ? | HTTPS-inspectie aanzetten? |
| `ui_https_confirm_body` | CoreLock will decrypt the connections of apps that trust its certificate and keep what they send on this phone. Most apps don’t trust it, so for them you’ll only see site names. Turn it off when you’re done. | سيفكّ CoreLock تشفير اتصالات التطبيقات التي تثق بشهادته، ويحفظ ما ترسله على هذا الهاتف. معظم التطبيقات لا تثق بها، فلن ترى منها إلا أسماء المواقع. أوقفه عندما تنتهي. | CoreLock déchiffrera les connexions des applis qui font confiance à son certificat et gardera ce qu’elles envoient sur ce téléphone. La plupart des applis ne lui font pas confiance : pour elles, vous ne verrez que les noms des sites. Désactivez-la quand vous avez terminé. | CoreLock ontsleutelt de verbindingen van apps die zijn certificaat vertrouwen en bewaart wat ze versturen op deze telefoon. De meeste apps vertrouwen het niet; van die apps zie je alleen sitenamen. Zet het uit als je klaar bent. |
| `ui_https_confirm_on` | Turn on | تشغيل | Activer | Aanzetten |
| `ui_https_install_cd` | Install the inspection certificate | ثبّت شهادة الفحص | Installer le certificat d’inspection | Het inspectiecertificaat installeren |

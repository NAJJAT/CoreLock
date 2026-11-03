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
| `ui_hero_off` | Protection is off | الحماية متوقفة | La protection est désactivée | Bescherming staat uit |
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
| `ui_attention_private_dns` | Private DNS is on. Apps can skip CoreLock. | DNS الخاص مفعّل. يمكن للتطبيقات تجاوز CoreLock. | Le DNS privé est activé. Des applis peuvent contourner CoreLock. | Privé-DNS staat aan. Apps kunnen CoreLock omzeilen. |
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

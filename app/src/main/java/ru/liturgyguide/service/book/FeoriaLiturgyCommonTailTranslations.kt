package ru.liturgyguide.service.book

/** Nineteen independently reviewed whole liturgy bodies. Labels, directions and optionality stay in the source. */
internal object FeoriaLiturgyCommonTailTranslations {
    const val SOURCE_CANDIDATE_SHA256 = "10830cfcd3ae032e453cab30a8cc1b6800684f987882617954a3f1d5e0b8b0e4"
    const val SEMANTIC_REVIEW_SHA256 = "3e6a71bbde72d35fc62f05406cd0ec3be8cc80f6bab776ab1a8dda7348934e21"
    const val APPROVED_FINAL_PROJECTION_SHA256 = "b17443aedd57b085579de25c10d4e85361a2cde12b0ebd47e879b71e0c2ec7e7"
    private const val ORIGINAL_SOURCE_URL = "https://azbyka.ru/worships/?date=2026-11-13&worship=liturgy&size=full&church=30"

    data class Record(val entry: FeoriaTranslationCatalog.Entry, val rank: Int,
        val originalSha256: String, val normalizedOriginalSha256: String, val russianSha256: String,
        val sourceBlockId: String, val sourceChapterId: String, val originalWords: Int, val newPrayerWords: Int)

    val records = listOf(
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-138ae7ce478230b61102",
            "Го́споди Бо́же Сил, Бо́же спасе́ния на́шего, при́зри в ми́лости на смире́нныя рабы́ Твоя́, услы́ши и поми́луй нас: се бо бра́ни хотя́щии ополчи́шася на Святу́ю Русь, ча́юще раздели́ти и погуби́ти еди́ный наро́д ея́. Воста́ни, Бо́же, в по́мощь лю́дем Твои́м и пода́ждь нам си́лою Твое́ю побе́ду. Ве́рным ча́дом Твои́м, о еди́нстве Ру́сския Це́ркве ревну́ющим, поспе́шествуй, в ду́хе братолю́бия укрепи́ их и от бед изба́ви. Запрети́ раздира́ющим во омраче́нии умо́в и ожесточе́нии серде́ц ри́зу Твою́, я́же есть Це́рковь Жива́го Бо́га, и за́мыслы их ниспрове́ргни. Благода́тию Твое́ю вла́сти предержа́щия ко вся́кому бла́гу наста́ви и му́дростию обогати́. Во́ины и вся защи́тники Оте́чества на́шего в за́поведех Твои́х утверди́, кре́пость ду́ха им низпосли́, от сме́рти, ран и плене́ния сохрани́. Лише́нныя кро́ва и в изгна́нии су́щия в до́мы введи́, а́лчущия напита́й, [жа́ждущия напои́], неду́гующия и стра́ждущия укрепи́ и исцели́, в смяте́нии и печа́ли су́щим наде́жду благу́ю и утеше́ние пода́ждь. Всем же во дни сия́ убие́нным и от ран и боле́зней сконча́вшимся проще́ние грехо́в да́руй и блаже́нное упокое́ние сотвори́. Испо́лни нас я́же в Тя ве́ры, наде́жды и любве́, возста́ви па́ки во всех страна́х Святы́я Руси́ мир и единомы́слие, друг ко дру́гу любо́вь обнови́ в лю́дех Твои́х, я́ко да еди́неми усты́ и еди́нем се́рдцем испове́мыся Тебе́, Еди́ному Бо́гу в Тро́ице сла́вимому. Ты бо еси́ заступле́ние, и побе́да, и спасе́ние упова́ющим на Тя, и Тебе́ сла́ву возсыла́ем, Отцу́, и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в. Ами́нь.",
            "Господи, Боже сил, Боже нашего спасения, милостиво взгляни на смиренных Твоих рабов, услышь и помилуй нас: вот желающие войны ополчились на Святую Русь, надеясь разделить и погубить ее единый народ. Восстань, Боже, на помощь Твоим людям и даруй нам Твоей силой победу. Помоги Твоим верным детям, ревнующим о единстве Русской Церкви, укрепи их в духе братской любви и избавь от бед. Останови тех, кто с помраченными умами и ожесточенными сердцами раздирает Твою ризу, которая есть Церковь Живого Бога, и ниспровергни их замыслы. Твоей благодатью наставь стоящих у власти на всякое добро и обогати их мудростью. Воинов и всех защитников нашего Отечества утверди в Твоих заповедях, ниспошли им крепость духа, сохрани от смерти, ран и плена. Лишенных крова и живущих в изгнании приведи в дома, голодных накорми, [жаждущих напои], болеющих и страдающих укрепи и исцели, пребывающим в смятении и печали даруй добрую надежду и утешение. Всем убитым в эти дни и умершим от ран и болезней даруй прощение грехов и блаженный покой. Наполни нас верой в Тебя, надеждой на Тебя и любовью к Тебе, вновь восстанови во всех странах Святой Руси мир и единомыслие, обнови в Твоих людях любовь друг к другу, чтобы мы едиными устами и единым сердцем прославляли Тебя, единого Бога, славимого в Троице. Ибо Ты - защита, победа и спасение для надеющихся на Тебя, и Тебе мы воздаем славу, Отцу, и Сыну, и Святому Духу, теперь и всегда, и во веки веков. Аминь.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            1, "212672476b906c6ac8c0ee019e70b1fdec3918805536cb2d72d6704c6f9aee3f",
            "138ae7ce478230b61102f4a776bd3a0f29aaf8bb2984154e3e0786e574368dbd",
            "2c33bd2e9dfb17ee85ff362cacef51e5d4f6a7bf3e8f4926f181096aff1ca63e",
            "liturgy-79f77e6ea6d3-1:ffd85bef95edf32a:1", "liturgy-79f77e6ea6d3-1", 230, 230),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-d09d568f5ec630982d6e",
            "Вели́каго господи́на и отца́ на́шего Кири́лла, / Святе́йшаго Патриа́рха Моско́вскаго и всея́ Ру́си, и господи́на на́шего преосвяще́ннейшаго (имяре́к), митрополи́та (или архиепи́скопа, или епи́скопа его́же есть о́бласть), / богохрани́мую страну́ на́шу Росси́йскую, / настоя́теля, бра́тию и прихо́жан свята́го хра́ма сего́, / и вся правосла́вныя христиа́ны, / Го́споди, сохрани́ их на мно́гая ле́та.",
            "Великого господина и отца нашего Кирилла, / Святейшего Патриарха Московского и всея Руси, и нашего господина преосвященнейшего (имярек), митрополита (или архиепископа, или епископа, в чьей области мы находимся), / нашу Российскую страну, хранимую Богом, / настоятеля, братию и прихожан этого святого храма, / и всех православных христиан, / Господи, сохрани их на многие годы.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            5, "dbd1417742031e9a75802e64d57ae9607fa5f287802b09ee1b9a5af6ef61f0d9",
            "d09d568f5ec630982d6ea6b4947d8f17f1b4a18d5e2101297625fcfb5bb6b61c",
            "8b91c30ce94c2ffd28cbb60500fa3daea5a25d107842d3acd451b46f82e6f25a",
            "liturgy-85d3b43f2d35-1:e4a329ef39518cf4:1", "liturgy-85d3b43f2d35-1", 46, 46),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-c5aa80820081a7b4e3f1",
            "В пе́рвых помяни́, Го́споди, вели́каго господи́на и отца́ на́шего Кири́лла, Святейшаго Патриарха Московскаго и всея Руси, и господи́на на́шего преосвяще́ннейшаго (имяре́к), митрополи́та (или архиепи́скопа, или епи́скопа его́же есть о́бласть), и́хже да́руй святы́м Твои́м це́рквам, в ми́ре, це́лых, честны́х, здра́вых, долгоде́нствующих, пра́во пра́вящих сло́во Твоея́ и́стины.",
            "Прежде всего помяни, Господи, великого господина и отца нашего Кирилла, Святейшего Патриарха Московского и всея Руси, и нашего господина преосвященнейшего (имярек), митрополита (или архиепископа, или епископа, в чьей области мы находимся), которых даруй Твоим святым церквам в мире, невредимыми, почтенными, здоровыми, долголетними, верно преподающими слово Твоей истины.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            6, "26ccd072190458139172a977582d90eeaca13f02afc8eca9b0770c8fe0763444",
            "c5aa80820081a7b4e3f108de9e349a3229b33d9a6d81f149a618c5b04c3f064d",
            "ef559dd0e801792a32352b364a975b52aba6f07c727b0402b95021780ca7231e",
            "liturgy-5d1f94f15d27-1:b94c59bab1824c4a:1", "liturgy-5d1f94f15d27-1", 45, 45),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-fe0b74f9b248993c2807",
            "Вели́каго господи́на и отца́ на́шего Кири́лла, Святе́йшаго Патриа́рха Моско́вскаго и всея́ Ру́си, и господи́на на́шего преосвяще́ннейшаго (имяре́к), митрополи́та (или архиепи́скопа, или епи́скопа его́же есть о́бласть), да помяне́т Госпо́дь Бог во Ца́рствии Свое́м всегда́, ны́не и при́сно, и во ве́ки веко́в.",
            "Великого господина и отца нашего Кирилла, Святейшего Патриарха Московского и всея Руси, и нашего господина преосвященнейшего (имярек), митрополита (или архиепископа, или епископа, в чьей области мы находимся), да помянет Господь Бог в Своем Царстве всегда, теперь и непрестанно, и во веки веков.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            7, "1a101f97b4cb35828f3553b3e72482e5d853c0b05c3e2e07dd8fda593f7102e8",
            "fe0b74f9b248993c28074d27ceca6952261d471baa02b8058055ce3c56e0cf43",
            "ab4165a5af932b0c03b6eab6b302da634215d114084968c647c855caa3e2ef4d",
            "liturgy-34065f45c2e1-1:cfaf23c5c9602444:1", "liturgy-34065f45c2e1-1", 40, 40),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-37a736118212463f3b5b",
            "Еще́ мо́лимся Тебе́, Го́споду и Спаси́телю на́шему, о е́же прия́ти моли́твы нас недосто́йных рабо́в Твои́х в сию́ годи́ну испыта́ния, прише́дшую на Русь Святу́ю, обыше́дше бо обыдо́ша ю́ врази́, и о е́же яви́ти спасе́ние Твое́, рцем вси: Го́споди, услы́ши и поми́луй.",
            "Еще молимся Тебе, нашему Господу и Спасителю, чтобы Ты принял молитвы нас, недостойных Твоих рабов, в этот час испытания, пришедший на Святую Русь, ибо враги окружили ее со всех сторон, и явил Твое спасение. Скажем все: Господи, услышь и помилуй.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            8, "f92ce864c5ec0d7a16ae360b1334c15f692472687b4e9af7e001143ed43d9aed",
            "37a736118212463f3b5b51516aa621b1660f6f8aa49b1ed9209c62bd54739fe3",
            "f957c3c0b197b1a69a222e3225fd484bf3fec764ea9e62afabdddb8cea0ef338",
            "liturgy-e8e07c76cf8b-1:64f165ca17ce54ee:1", "liturgy-e8e07c76cf8b-1", 40, 40),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-d5c5e1e25aa045b4b14b",
            "Богоро́дичен: Предста́тельство христиа́н непосты́дное, хода́тайство ко Творцу́ непрело́жное, не пре́зри гре́шных моле́ний гла́сы, но предвари́, я́ко Блага́я, на по́мощь на́с, ве́рно зову́щих Ти́: ускори́ на моли́тву, и потщи́ся на умоле́ние, предста́тельствующи при́сно, Богоро́дице, чту́щих Тя́.",
            "Богородичен: Надежная Защитница христиан, неизменная Ходатаица перед Творцом, не пренебреги молитвенными голосами грешников, но, как Благая, поспеши на помощь нам, с верой взывающим к Тебе: поспеши с молитвой и с усердием вознеси моление, Богородица, всегда заступающаяся за почитающих Тебя.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            9, "abeac179553936a045e06047fdab549f624e5c2b1380feb4ec07ccc4f227f66a",
            "d5c5e1e25aa045b4b14b86d75091e74602fd58d3e8c1700493fa402d9bbb742b",
            "35508fc3109d95b92dc1e54d4644c141fa45d11c4b51285c4826f395e4c7f8bc",
            "liturgy-ddd29a930086-1:d20a41a6353d2e3a:1", "liturgy-ddd29a930086-1", 35, 34),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-42edeedd51bab020910e",
            "Еще́ мо́лимся о е́же благосе́рдием и ми́лостию призре́ти на во́инство и вся защи́тники Оте́чества на́шего, и о е́же утверди́ти нас всех в ве́ре, единомы́слии, здра́вии и си́ле ду́ха, рцем вси: Го́споди, услы́ши и ми́лостивно поми́луй.",
            "Еще молимся, чтобы Ты с добротой и милостью взглянул на воинство и всех защитников нашего Отечества и укрепил всех нас в вере, единомыслии, здоровье и силе духа. Скажем все: Господи, услышь и милостиво помилуй.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            10, "d5b8986c916e06ff65a0a369f73d9beae0318ddec2f0b2a96a90b3037376677c",
            "42edeedd51bab020910e78f947b1ecdae515b9d0e914a82939ccb319190b642d",
            "b5a510f92ca69d6b7349b5f657c903c4b1196fa5f80f9c3389d31870a98a7b40",
            "liturgy-e8e07c76cf8b-1:195fac933e296ed6:1", "liturgy-e8e07c76cf8b-1", 35, 35),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-01fb87cf2eb8d2ab1625",
            "Преосвяще́нныя митрополи́ты, архиепи́скопы и епи́скопы, и весь свяще́ннический и мона́шеский чин, при́чет свята́го хра́ма сего́, и всех вас правосла́вных христиа́н, да помяне́т Госпо́дь Бог во Ца́рствии Свое́м, всегда́, ны́не и при́сно, и во ве́ки веко́в.",
            "Преосвященных митрополитов, архиепископов и епископов, всех священнослужителей и монашествующих, причт этого святого храма и всех вас, православных христиан, да помянет Господь Бог в Своем Царстве всегда, теперь и непрестанно, и во веки веков.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            11, "0689cf125bf5ec17c2a32c5a37f54ee373d2eda53c1c62d4ec9910b834a2cae1",
            "01fb87cf2eb8d2ab1625fc198bac889a5f9d5783a719bc1a9a56f31b66cc7f46",
            "3db79da4ea327b8eb5cc6f0a62ba6d2194792c5adfd0aa4bae84d0f9adaedf30",
            "liturgy-34065f45c2e1-1:810247a95fe4a801:1", "liturgy-34065f45c2e1-1", 35, 35),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-a947cfb57c7b399842e4",
            "О Вели́ком Господи́не и отце́ на́шем, Святе́йшем Патриа́рхе Кири́лле, и о господи́не на́шем преосвяще́ннейшем митрополи́те (или архиепи́скопе, или епи́скопе) (имяре́к), честне́м пресви́терстве, во Христе́ диа́констве и о всем при́чте и лю́дех, Го́споду помо́лимся.",
            "О великом господине и отце нашем, Святейшем Патриархе Кирилле, о нашем господине преосвященнейшем митрополите (или архиепископе, или епископе) (имярек), о почтенных священниках, о диаконах во Христе, обо всем причте и народе Господу помолимся.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            12, "076e7b56709a9b82f8067c52bb63d0f9fc87ca74c5ca980a9098b2770aca3fa8",
            "a947cfb57c7b399842e466d5d0ec0e5519e5af753970c0a8c9fdc600abbe6c4b",
            "ff81d34850323fddf3d74451f5c116fc06456456ba76b2499797578ce738decd",
            "liturgy-af63642abcec-1:f864d71a828206db:1", "liturgy-af63642abcec-1", 33, 33),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-594f35c6b48fd95ee818",
            "Еще́ мо́лимся о Вели́ком Господи́не и Отце́ на́шем Святе́йшем Патриа́рхе Кири́лле, и о господи́не на́шем преосвяще́ннейшем митрополи́те (или архиепи́скопе, или епи́скопе) (имяре́к), и всей во Христе́ бра́тии на́шей.",
            "Еще молимся о великом господине и отце нашем, Святейшем Патриархе Кирилле, о нашем господине преосвященнейшем митрополите (или архиепископе, или епископе) (имярек) и обо всех наших братьях во Христе.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            13, "29708e66dce9df741f3d3c58449c041a3f64b0170eeb897d4909fb0bc74aa529",
            "594f35c6b48fd95ee81803dff23a9fab1d97fa7373175fef8be8b6c66aff8832",
            "4127a4686d91cbb4e198c5caa7a07b37080569498632307977df70be41ed2e5c",
            "liturgy-e8e07c76cf8b-1:4e06f9311343c366:1", "liturgy-e8e07c76cf8b-1", 28, 28),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-f9fb34a88462198417fd",
            "Бо́г, моли́твами свята́го, сла́внаго, всехва́льнаго Апо́стола и Евангели́ста Луки́, да да́ст тебе́ глаго́л благовеству́ющему си́лою мно́гою во исполне́ние Ева́нгелия Возлю́бленнаго Сы́на Своего́, Го́спода на́шего Иису́са Христа́.",
            "Бог, молитвами святого, славного и достойного всякой хвалы Апостола и Евангелиста Луки, да даст тебе, возвещающему Благую весть, слово с великой силой для исполнения Евангелия Своего Возлюбленного Сына, Господа нашего Иисуса Христа.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            14, "91109918434e943bf59bab0950db7b2b37531281dbe14957274612167a94d166",
            "f9fb34a88462198417fd6971841a9ab0421dc88cc9bb0eba923c923158e70149",
            "545d402beff94a895ff157cf96f89a80aa1247ebf337aef9d94b440caf192302",
            "liturgy-77a6b473eb0a-1:feb1120d6e6f0a85:1", "liturgy-77a6b473eb0a-1", 26, 26),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-eaa534c1db64c080ffca",
            "Возвышая голос и показывая народу св. Чашу: Всегда́, ны́не и при́сно, и во ве́ки веко́в.",
            "Возвышая голос и показывая народу св. Чашу: Всегда, теперь и непрестанно, и во веки веков.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            15, "ae42deeb0e68f5e258eb1862cd5017853e58585fdef8e53b1f4d195c60c11f03",
            "eaa534c1db64c080ffca9a6c5cf4494dbe950f1f86e1cf0b3ab83eed13f4c01a",
            "458bf8be17006d3650433c04a0af22243b7f6259ae20fb5d3cb390a43b318818",
            "liturgy-5b5535a3d8e6-1:4252fc4339c9417f:1", "liturgy-5b5535a3d8e6-1", 15, 8),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-eff377cf3100ed34e3d9",
            "Святы́й Бо́же, Святы́й Кре́пкий, Святы́й Безсме́ртный, поми́луй нас. (Tрижды и три поклона)",
            "Святой Боже, Святой Крепкий, Святой Бессмертный, помилуй нас. (Трижды и три поклона)", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            16, "af1827804e1f0416292d290840cd63aeef00a3c7f4ca501850e8b8fd31254212",
            "eff377cf3100ed34e3d9124ef11e2c0b0034ec9994aea5713e21261a04e8ed26",
            "f6d5ae6efaf02c5baaa8b1231ba1a4904255797d25a3b24395fd1daba7eaa875",
            "liturgy-ddd29a930086-1:9b21811c2261f632:1", "liturgy-ddd29a930086-1", 12, 8),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-e297d1dd7fc89118aedf",
            "Благослови́, влады́ко, благовести́теля свята́го Апо́стола и Евангели́ста Луки́.",
            "Благослови, владыка, возвещающего Евангелие святого Апостола и Евангелиста Луки.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            17, "31e0dbdaae54e0fe1d19000fa689f39fadf372e9b83fa9d70450d01aa6465910",
            "e297d1dd7fc89118aedf835486afbc7f0c77c00324857323c96a18d3dc34db1d",
            "acccaa69c319987d54c2af4bb04741bef6a1bd986e4300c6a4eb3f8face509aa",
            "liturgy-77a6b473eb0a-1:df021cf012826dd4:1", "liturgy-77a6b473eb0a-1", 8, 8),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-3af18a8afffdd2f65071",
            "От Луки́ свята́го Ева́нгелия чте́ние.",
            "Чтение святого Евангелия от Луки.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            18, "6a1d50b4c4a94f48b0143547e3864e8e244b1d03073e066e1662617d85f9b7f9",
            "3af18a8afffdd2f65071065b1220ce95bf004a8550f6614438e3401785be9a59",
            "d5159c2fb3a88f1fa52387fa93917a86b04ad96492d5db2bac6e9b4f3937dcb5",
            "liturgy-77a6b473eb0a-1:55d0b7ac336a0842:1", "liturgy-77a6b473eb0a-1", 5, 0),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-aab158670a0abec7ca71",
            "Го́споди, поми́луй. (12 раз)",
            "Господи, помилуй. (12 раз)", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            19, "76941928b93885f625fa70648e02067471749033a4889f8ff8f18886d832b787",
            "aab158670a0abec7ca7196a5f187ecffa24cf4702730bd0b54a30f83e4fcb0da",
            "d61ea57ea179fc88706df446a53ffc1cf5b20c259b9e904e7d97765f4dbbc7cf",
            "liturgy-ddd29a930086-1:356f9fd92029ca2b:1", "liturgy-ddd29a930086-1", 4, 0),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-1ed7d1dc0916177a3c60",
            "И во ве́ки веко́в.",
            "И во веки веков.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            20, "6830f7a36bb00255e8055e3d24538a95c1db4a379c008064dda79519decb7187",
            "1ed7d1dc0916177a3c600a60a4417cb47ce1ac09c4463190dbeae0eeda4dc10b",
            "04076d29d8c97c628572c92c020729dd98c46047efe6e00781bb16af817933d4",
            "liturgy-06714216a0de-1:b33e7e8090648b2e:1", "liturgy-06714216a0de-1", 4, 0),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-016b022ab6e80789172c",
            "И всех, и вся.",
            "И всех мужчин, и всех женщин.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            21, "0ae5085b516324791991d4c7d0fb4d86090bc9a837befd1dd6350268d930de58",
            "016b022ab6e80789172cbbb62a57d9d4a5d2f5269eceddd0c5ada5df78460762",
            "71110754e21f0fba3136a74346fc95010cc3acf66a6a2e1bfb7f15106b12947b",
            "liturgy-5d1f94f15d27-1:5efd846a32d2d6b5:1", "liturgy-5d1f94f15d27-1", 4, 4),
        Record(FeoriaTranslationCatalog.Entry("liturgy-common-tail-c18737bf125331c8767e",
            "Аллилу́иа. Аллилу́иа. Аллилу́иа.",
            "Хвалите Господа. Хвалите Господа. Хвалите Господа.", revision = 7, originalSourceUrl = ORIGINAL_SOURCE_URL),
            22, "0c640e17b79b9916536c814d2e82f2a7dec6a89edaea863bccf8561f4a091e32",
            "c18737bf125331c8767e03152b2eb59629646031445bba6407224c0f7d03c410",
            "fa5f8bc01f346f1d4b01ed3e563742472d2426d16e36d6bef150cf339ae2b39e",
            "liturgy-21d28bc25f3d-1:8be1a4d4f6590f85:1", "liturgy-21d28bc25f3d-1", 3, 3)
    )
    val entries: List<FeoriaTranslationCatalog.Entry> = records.map { it.entry }

    init {
        require(records.size == 19 && records.map { it.entry.id }.distinct().size == 19)
        require(records.map { it.normalizedOriginalSha256 }.distinct().size == 19)
        require(records.map { it.rank }.toSet() == setOf(1) + (5..22).toSet())
        require(records.sumOf { it.originalWords } == 648 && records.sumOf { it.newPrayerWords } == 623)
        require(records.count { it.newPrayerWords > 0 } == 16)
        require(records.all { record ->
            BookText.sha256(record.entry.original) == record.originalSha256 &&
                BookText.sha256(BookText.normalized(record.entry.original)) == record.normalizedOriginalSha256 &&
                BookText.sha256(record.entry.russian) == record.russianSha256 &&
                record.sourceBlockId.isNotBlank() && record.sourceChapterId.isNotBlank()
        })
    }
    private val byNormalizedOriginal = records.associateBy { it.normalizedOriginalSha256 }

    /** Whole lexical body only: do not remove labels, roles, counts, negations or mixed-script glyphs. */
    fun recognizes(original: String, translation: BookTranslation): Boolean {
        if (translation.source != BookTranslationIndex.FEORIA_TRANSLATION_SOURCE || translation.meaning != null) return false
        val record = byNormalizedOriginal[BookText.sha256(BookText.normalized(original))] ?: return false
        return translation.russian == record.entry.russian && BookText.sha256(translation.russian) == record.russianSha256
    }
}

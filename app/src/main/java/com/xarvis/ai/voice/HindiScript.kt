package com.xarvis.ai.voice

/**
 * Hinglish written in English letters ("aap kaise hain, sir?") read by the Hindi voice sounded
 * like someone reading a foreign language (Rex: "not native"): the voice guesses at Roman
 * spellings. So before speaking, common Hindi words are rewritten in Devanagari, which the voice
 * reads like a native speaker. English words ("sir", "file", "update") stay as they are, and
 * the Hindi voice says them the way an Indian speaker does, like the Hindi dub mixes them.
 * Only used for speech; the chat still shows what Gemma wrote.
 */
object HindiScript {

    private val WORDS: Map<String, String> = """
        aap=आप aapka=आपका aapki=आपकी aapke=आपके aapko=आपको aapse=आपसे aapne=आपने apka=आपका apki=आपकी apke=आपके apko=आपको
        main=मैं mai=मैं mein=में me=में mujhe=मुझे mujhse=मुझसे mera=मेरा meri=मेरी mere=मेरे maine=मैंने
        hum=हम humein=हमें hamein=हमें hamara=हमारा hamari=हमारी hamare=हमारे humne=हमने
        tum=तुम tumhe=तुम्हें tumhein=तुम्हें tumhara=तुम्हारा tumhari=तुम्हारी tumhare=तुम्हारे tu=तू
        yeh=यह ye=ये yah=यह woh=वह wo=वो vo=वो voh=वह inka=इनका unka=उनका unki=उनकी unke=उनके usse=उससे isse=इससे
        iska=इसका iski=इसकी iske=इसके uska=उसका uski=उसकी uske=उसके isko=इसको usko=उसको ise=इसे inhe=इन्हें unhe=उन्हें unhone=उन्होंने
        hai=है hain=हैं hoon=हूँ hun=हूँ ho=हो tha=था thi=थी hoga=होगा hogi=होगी honge=होंगे hota=होता hoti=होती hote=होते
        hua=हुआ hui=हुई hue=हुए hona=होना hone=होने raha=रहा rahi=रही rahe=रहे rehna=रहना rahiye=रहिए
        kya=क्या kyun=क्यों kyon=क्यों kyunki=क्योंकि kaise=कैसे kaisa=कैसा kaisi=कैसी kab=कब kahan=कहाँ kaha=कहा kaun=कौन kitna=कितना kitni=कितनी kitne=कितने konsa=कौनसा kaunsa=कौनसा
        nahi=नहीं nahin=नहीं na=ना mat=मत haan=हाँ han=हाँ ji=जी jee=जी
        aur=और ya=या lekin=लेकिन par=पर magar=मगर toh=तो to=तो bhi=भी hi=ही sirf=सिर्फ bas=बस agar=अगर phir=फिर fir=फिर jab=जब tab=तब
        ka=का ki=की ke=के ko=को se=से ne=ने pe=पे tak=तक liye=लिए lie=लिए saath=साथ sath=साथ bina=बिना wala=वाला wali=वाली wale=वाले
        sab=सब sabhi=सभी kuch=कुछ kuchh=कुछ koi=कोई kisi=किसी kis=किस sabse=सबसे bahut=बहुत bohot=बहुत bahot=बहुत zyada=ज़्यादा jyada=ज़्यादा kam=कम thoda=थोड़ा thodi=थोड़ी thode=थोड़े
        theek=ठीक thik=ठीक accha=अच्छा acha=अच्छा achha=अच्छा acchi=अच्छी achhi=अच्छी acche=अच्छे achhe=अच्छे bura=बुरा badhiya=बढ़िया badiya=बढ़िया shandaar=शानदार zabardast=ज़बरदस्त
        bilkul=बिल्कुल zaroor=ज़रूर jaroor=ज़रूर zarur=ज़रूर shayad=शायद sach=सच sahi=सही galat=गलत pakka=पक्का asaan=आसान mushkil=मुश्किल
        abhi=अभी ab=अब aaj=आज kal=कल parso=परसों raat=रात din=दिन subah=सुबह shaam=शाम dopahar=दोपहर waqt=वक़्त samay=समय der=देर jaldi=जल्दी pehle=पहले baad=बाद hamesha=हमेशा kabhi=कभी
        yahan=यहाँ yahaan=यहाँ wahan=वहाँ wahaan=वहाँ idhar=इधर udhar=उधर andar=अंदर bahar=बाहर upar=ऊपर neeche=नीचे paas=पास
        kar=कर karo=करो karna=करना karne=करने karta=करता karti=करती karte=करते kiya=किया kiye=किए ki=की karunga=करूँगा karungi=करूँगी karenge=करेंगे kariye=करिए kijiye=कीजिए karein=करें karen=करें
        de=दे do=दो dena=देना diya=दिया dijiye=दीजिए dunga=दूँगा denge=देंगे le=ले lo=लो lena=लेना liya=लिया lijiye=लीजिए
        ja=जा jao=जाओ jana=जाना jaana=जाना gaya=गया gayi=गई gaye=गए jaiye=जाइए jayega=जाएगा jaayega=जाएगा jaenge=जाएँगे aa=आ aao=आओ aana=आना aaya=आया aayi=आई aaiye=आइए aayega=आएगा
        dekh=देख dekho=देखो dekhna=देखना dekhiye=देखिए dekha=देखा dekhte=देखते sun=सुन suno=सुनो suniye=सुनिए suna=सुना bol=बोल bolo=बोलो boliye=बोलिए bola=बोला bolna=बोलना
        bata=बता batao=बताओ bataiye=बताइए batana=बताना bataya=बताया bataunga=बताऊँगा samajh=समझ samjha=समझा samjhe=समझे samjhiye=समझिए soch=सोच socha=सोचा sochiye=सोचिए
        chahiye=चाहिए chahte=चाहते chahta=चाहता chahti=चाहती sakta=सकता sakti=सकती sakte=सकते sakenge=सकेंगे mil=मिल mila=मिला milega=मिलेगा milegi=मिलेगी
        chal=चल chalo=चलो chaliye=चलिए chalta=चलता rakh=रख rakho=रखो rakhiye=रखिए rakha=रखा likh=लिख likho=लिखो likha=लिखा padh=पढ़ padho=पढ़ो padha=पढ़ा bhej=भेज bhejo=भेजो bheja=भेजा bhejiye=भेजिए
        khol=खोल kholo=खोलो kholiye=खोलिए khola=खोला band=बंद dhoondh=ढूँढ dhundh=ढूँढ dhundho=ढूँढो dhoondho=ढूँढो laga=लगा lagta=लगता lagti=लगती lagega=लगेगा
        kaam=काम baat=बात baatein=बातें cheez=चीज़ cheezein=चीज़ें tarah=तरह taraf=तरफ़ jagah=जगह ghar=घर log=लोग dost=दोस्त duniya=दुनिया zindagi=ज़िंदगी dil=दिल dimaag=दिमाग dimag=दिमाग
        madad=मदद sawaal=सवाल sawal=सवाल jawaab=जवाब jawab=जवाब khabar=ख़बर naam=नाम paisa=पैसा paise=पैसे khana=खाना paani=पानी pani=पानी neend=नींद safar=सफ़र mausam=मौसम
        shukriya=शुक्रिया dhanyavaad=धन्यवाद dhanyawad=धन्यवाद namaste=नमस्ते namaskar=नमस्कार maaf=माफ़ kripya=कृपया khush=ख़ुश khushi=ख़ुशी pareshan=परेशान
        haal=हाल taiyaar=तैयार taiyar=तैयार tayyar=तैयार sirf=सिर्फ ek=एक do=दो teen=तीन char=चार paanch=पाँच saare=सारे saara=सारा poora=पूरा pura=पूरा naya=नया nayi=नई purana=पुराना
        bada=बड़ा badi=बड़ी bade=बड़े chhota=छोटा chota=छोटा chhoti=छोटी choti=छोटी lamba=लंबा hisaab=हिसाब matlab=मतलब yaani=यानी waise=वैसे aise=ऐसे jaise=जैसे vaise=वैसे
        mazaak=मज़ाक mazak=मज़ाक arre=अरे haha=हाहा wah=वाह waah=वाह oh=ओह achcha=अच्छा yaar=यार janab=जनाब huzoor=हुज़ूर sahab=साहब saheb=साहब
    """.trim().split(Regex("""\s+""")).associate { pair ->
        val (roman, devanagari) = pair.split("=", limit = 2)
        roman to devanagari
    }

    private val WORD = Regex("""[A-Za-z]+""")

    /** [text] with the Hindi words written in Devanagari, ready for the Hindi voice. */
    fun forSpeech(text: String): String =
        WORD.replace(text) { m -> WORDS[m.value.lowercase()] ?: m.value }
}

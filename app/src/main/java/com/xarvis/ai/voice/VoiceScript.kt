package com.xarvis.ai.voice

/**
 * The sentences Rex reads aloud to train XARVIS's voice on his own (see RecordVoiceActivity).
 * English ones are written as spoken. Hindi ones are shown to Rex in English letters (how he
 * types) and saved for training in Devanagari, which the Hindi voice model reads.
 * Numbers are spelled out, since the training reads the words.
 */
object VoiceScript {

    class Line(val show: String, val say: String = show)

    val english: List<Line> = """
        Good morning, sir. The coffee is ready, and so is your schedule.
        I have checked the weather. You will want a jacket today.
        Your battery is at forty percent. I suggest charging it soon.
        Shall I call Atiq, or would you rather send him a message?
        The meeting starts in fifteen minutes. Traffic looks light.
        I found three jobs that match your experience in Dubai.
        That is an excellent question, and I have an even better answer.
        Welcome home, sir. Everything is exactly where you left it.
        I am afraid the file is locked with a password.
        Would you like me to set an alarm for six thirty tomorrow?
        The Wi-Fi is on, the Bluetooth is off, and the lights are yours to command.
        I have saved the report to your Downloads folder.
        Your flight to Mumbai leaves at nine forty in the evening.
        Of course. Consider it done.
        I would never question your judgement, sir. Out loud.
        The timer is set for ten minutes. I will let you know.
        There is a new version of me available. Shall I update?
        Your wife called twice. I thought you should know.
        Let me think about that for a moment.
        Here is what I found on Wikipedia about the Burj Khalifa.
        The temperature outside is thirty eight degrees. Stay hydrated.
        I can open WhatsApp and type the message for you.
        Sorry, I did not catch that. Could you say it again?
        That sounds like a plan. A slightly dangerous one, but a plan.
        Opening the camera now. Smile, sir.
        Your next payment is due on the fifth of October.
        I have added that to my memory. I will not forget it.
        The document has twelve pages. Shall I summarise it?
        According to my calculations, you need more sleep.
        Right away, sir. Shall I play some music while you work?
        I checked LinkedIn. There are new openings in Abu Dhabi and Riyadh.
        Just a moment. The model is still waking up.
        You have two unread messages and one missed call.
        The distance from here to the airport is about twenty two kilometres.
        I am running entirely on your phone. No cloud, no spying.
        Would you like the answer in English or in Hindi?
        Excellent choice, sir. I would have picked the same.
        Everything is under control. Mostly.
        Your location is Al Barsha, near the Mall of the Emirates.
        I will remind you at five o'clock in the evening.
        Please hold the phone still while I look at the photo.
        This picture shows a white car parked beside a palm tree.
        I have converted the spreadsheet into a PDF.
        Do you want me to read the whole thing, or just the highlights?
        The flashlight is on. Try not to blind anyone.
        I am connected to the other phone over Tailscale.
        That is not in my records yet. Would you like to tell me?
        You asked me to remember that your passport expires in March.
        Hello, sir. What are we building today?
        On it. This will only take a second.
        I believe that is the fourth coffee today, sir.
        Your calendar is clear this afternoon. A rare sight.
        I have muted myself. Tap the speaker when you want me back.
        Shall I search for flights, hotels, or both?
        Rain is expected tomorrow morning, around eight.
        Your phone has plenty of storage left. About forty gigabytes.
        Pardon me, sir, but that email could use a friendlier tone.
        Very well. I will keep it short.
        The quick brown fox jumps over the lazy dog.
        Pack my box with five dozen liquor jugs.
        How vexingly quick daft zebras jump.
        She sells sea shells by the sea shore.
        Peter Piper picked a peck of pickled peppers.
        Around the rugged rocks the ragged rascal ran.
        A big black bug bit a big black bear.
        Thirty three thousand people think that Thursday is their thirtieth birthday.
        Which witch wished which wicked wish?
        Unique New York, you know you need unique New York.
        The sixth sick sheikh's sixth sheep is sick.
        Red lorry, yellow lorry, red lorry, yellow lorry.
        One, two, three, four, five, six, seven, eight, nine, ten.
        Monday, Tuesday, Wednesday, Thursday, Friday, Saturday and Sunday.
        January, February, March, April, May and June.
        July, August, September, October, November and December.
        The answer is simple: yes, but not today.
        Really? You want to do that at two in the morning?
        Well done, sir. That worked perfectly.
        Oh no. That did not go as planned.
        Hmm. Interesting. Tell me more.
        Absolutely not. Well, perhaps. Let me check.
        I am XARVIS, your personal assistant, built by Rex.
        I live on this phone, and I learn a little every day.
        My brain is called Gemma, but my personality is all yours.
        Shall I make a Word document with these notes?
        I have written the letter. Take a look before you send it.
        The price has gone up by twelve percent since last month.
        You have walked six thousand steps today. Not bad.
        That was a joke, sir. I am told they are funnier when explained.
        Please speak after the beep.
        Listening. Go ahead.
        I heard you say, call home. Calling now.
        The volume is at maximum. Your neighbours thank you.
        It is a quarter past eleven. Time for bed, perhaps?
        Security check complete. Both phones are paired and encrypted.
        I cannot press buttons inside other apps, but I can open them for you.
        That company is hiring engineers with five years of experience.
        Your salary slip is ready to download.
        Would you like to hear a fun fact? Octopuses have three hearts.
        The sun rises at six ten and sets at six fifteen.
        Driving to work will take about thirty five minutes.
        I have found your file. It was hiding in the Downloads folder.
        Let us try that again, a little more slowly this time.
        Thank you, sir. I do try.
        I am sorry, that is beyond my abilities for now.
        Every great idea starts as a slightly crazy one.
        Charging complete. Fully powered and ready for duty.
        Shall I translate that into Hindi for you?
        The package was delivered this morning at your door.
        Water boils at one hundred degrees Celsius.
        Mount Everest is eight thousand eight hundred and forty eight metres tall.
        I would recommend leaving ten minutes early.
        That number is not in your contacts. Shall I save it?
        Opening Google Maps with directions to the office.
        Your screen time today is four hours. Just saying.
        I have made a list: bread, milk, eggs, and patience.
        Loading the smart brain. This may take a minute.
        Done. Anything else I can do for you?
        The world is big, sir, but I will help you find your way.
        Good night, sir. I will keep watch.
        Is it serious, or can it wait until the morning?
        Congratulations! That is wonderful news.
        I understand. Take your time.
        The engine is ready. Your voice is next.
        Could you repeat the last part? The music was loud.
        Here is a summary in three points.
        First, check the date. Second, sign the page. Third, send it back.
        Honestly, sir, I think you are overthinking this.
        Your fingers are faster than my processor today.
        A new message from Ali: see you at eight.
        I will read it to you: the meeting has been moved to Friday.
        Excuse me, sir. Your tea is getting cold.
        Starting a new chat. The old one is saved in the menu.
        Brilliant. Let us get to work.
    """.trimIndent().lines().filter { it.isNotBlank() }.map { Line(it.trim()) }

    val hindi: List<Line> = """
        Namaste sir, main XARVIS hoon. Bataiye, aaj kya karna hai?|नमस्ते सर, मैं ज़ार्विस हूँ। बताइए, आज क्या करना है?
        Ji sir, bilkul. Abhi kar deta hoon.|जी सर, बिल्कुल। अभी कर देता हूँ।
        Aapki battery chaalees percent hai. Charge kar lijiye.|आपकी बैटरी चालीस परसेंट है। चार्ज कर लीजिए।
        Aaj mausam garam hai, paani peete rahiye.|आज मौसम गरम है, पानी पीते रहिए।
        Main aapke liye hamesha taiyaar hoon.|मैं आपके लिए हमेशा तैयार हूँ।
        Kya main Atiq ko call karoon?|क्या मैं अतीक़ को कॉल करूँ?
        Sab theek hai, sir. Chinta mat kijiye.|सब ठीक है, सर। चिंता मत कीजिए।
        Yeh sawaal toh bahut accha hai.|यह सवाल तो बहुत अच्छा है।
        Ek minute, main soch raha hoon.|एक मिनट, मैं सोच रहा हूँ।
        Maaf kijiye, main samjha nahin. Phir se boliye.|माफ़ कीजिए, मैं समझा नहीं। फिर से बोलिए।
        Aapki meeting pandrah minute mein shuru hogi.|आपकी मीटिंग पंद्रह मिनट में शुरू होगी।
        Maine file Downloads mein save kar di hai.|मैंने फ़ाइल डाउनलोड्स में सेव कर दी है।
        Kal subah chhe baje ka alarm laga doon?|कल सुबह छह बजे का अलार्म लगा दूँ?
        Aapki biwi ne do baar phone kiya tha.|आपकी बीवी ने दो बार फ़ोन किया था।
        Arre sir, yeh toh kamaal ho gaya!|अरे सर, यह तो कमाल हो गया!
        Aap chinta mat kijiye, main hoon na.|आप चिंता मत कीजिए, मैं हूँ ना।
        Dubai mein teen naukriyan mili hain.|दुबई में तीन नौकरियाँ मिली हैं।
        Kya aap chahte hain ki main yeh message bhej doon?|क्या आप चाहते हैं कि मैं यह मैसेज भेज दूँ?
        Aaj ka din bahut accha jaane wala hai.|आज का दिन बहुत अच्छा जाने वाला है।
        Sir, aapki chai thandi ho rahi hai.|सर, आपकी चाय ठंडी हो रही है।
        Mujhe lagta hai aapko thoda aaram karna chahiye.|मुझे लगता है आपको थोड़ा आराम करना चाहिए।
        Bahut badhiya, sir. Kaam ho gaya.|बहुत बढ़िया, सर। काम हो गया।
        Abhi dekhta hoon, bas ek second.|अभी देखता हूँ, बस एक सेकंड।
        Yeh kaam mere bas ka nahin hai, abhi ke liye.|यह काम मेरे बस का नहीं है, अभी के लिए।
        Main aapki madad karne ke liye yahan hoon.|मैं आपकी मदद करने के लिए यहाँ हूँ।
        Aapka agla payment paanch October ko hai.|आपका अगला पेमेंट पाँच अक्टूबर को है।
        Maine yeh baat yaad rakh li hai.|मैंने यह बात याद रख ली है।
        Kya aapko poori file sunni hai, ya sirf khaas baatein?|क्या आपको पूरी फ़ाइल सुननी है, या सिर्फ़ ख़ास बातें?
        Ghar pe swagat hai, sir.|घर पे स्वागत है, सर।
        Bahar baarish ho rahi hai, chhata le jaiye.|बाहर बारिश हो रही है, छाता ले जाइए।
        Sir, yeh chauthi coffee hai aaj.|सर, यह चौथी कॉफ़ी है आज।
        Aapka phone bilkul surakshit hai.|आपका फ़ोन बिल्कुल सुरक्षित है।
        Main is phone par rehta hoon, cloud par nahin.|मैं इस फ़ोन पर रहता हूँ, क्लाउड पर नहीं।
        Kya main WhatsApp khol doon?|क्या मैं व्हाट्सऐप खोल दूँ?
        Airport yahan se baees kilometre door hai.|एयरपोर्ट यहाँ से बाईस किलोमीटर दूर है।
        Thoda dheere boliye, main sun raha hoon.|थोड़ा धीरे बोलिए, मैं सुन रहा हूँ।
        Shukriya, sir. Main koshish karta hoon.|शुक्रिया, सर। मैं कोशिश करता हूँ।
        Yeh mazaak tha, sir. Samjhane se aur mazedaar ho jaata hai.|यह मज़ाक था, सर। समझाने से और मज़ेदार हो जाता है।
        Ek, do, teen, chaar, paanch, chhe, saat, aath, nau, das.|एक, दो, तीन, चार, पाँच, छह, सात, आठ, नौ, दस।
        Somvaar, Mangalvaar, Budhvaar, Guruvaar, Shukravaar, Shanivaar, Ravivaar.|सोमवार, मंगलवार, बुधवार, गुरुवार, शुक्रवार, शनिवार, रविवार।
        Kya haal hai, sir? Sab khairiyat?|क्या हाल है, सर? सब ख़ैरियत?
        Aapki awaaz sunkar accha laga.|आपकी आवाज़ सुनकर अच्छा लगा।
        Main samajh gaya. Aap aaram se boliye.|मैं समझ गया। आप आराम से बोलिए।
        Yeh raasta sabse chhota hai.|यह रास्ता सबसे छोटा है।
        Office pahunchne mein paintees minute lagenge.|ऑफ़िस पहुँचने में पैंतीस मिनट लगेंगे।
        Maine aapke liye ek list bana di hai.|मैंने आपके लिए एक लिस्ट बना दी है।
        Doodh, bread, ande, aur thoda sabr.|दूध, ब्रेड, अंडे, और थोड़ा सब्र।
        Kya aapko Hindi mein jawab chahiye ya English mein?|क्या आपको हिंदी में जवाब चाहिए या इंग्लिश में?
        Wah sir, kya baat hai!|वाह सर, क्या बात है!
        Ji haan, yeh bilkul sahi hai.|जी हाँ, यह बिल्कुल सही है।
        Nahin sir, yeh galat lag raha hai.|नहीं सर, यह ग़लत लग रहा है।
        Shayad aapko ek baar phir check karna chahiye.|शायद आपको एक बार फिर चेक करना चाहिए।
        Chaliye, kaam shuru karte hain.|चलिए, काम शुरू करते हैं।
        Aaj raat ko jaldi so jaiye, sir.|आज रात को जल्दी सो जाइए, सर।
        Shubh raatri, sir. Main pehra doonga.|शुभ रात्रि, सर। मैं पहरा दूँगा।
        Suprabhat, sir. Naya din, nayi shuruaat.|सुप्रभात, सर। नया दिन, नई शुरुआत।
        Mera dimaag Gemma hai, par andaaz poora aapka.|मेरा दिमाग़ जेमा है, पर अंदाज़ पूरा आपका।
        Ali ka message aaya hai: aath baje milte hain.|अली का मैसेज आया है: आठ बजे मिलते हैं।
        Maine chitthi likh di hai, ek baar padh lijiye.|मैंने चिट्ठी लिख दी है, एक बार पढ़ लीजिए।
        Is photo mein ek safed gaadi khajoor ke ped ke paas khadi hai.|इस फ़ोटो में एक सफ़ेद गाड़ी खजूर के पेड़ के पास खड़ी है।
        Flashlight chalu hai. Kisi ki aankhon mein mat daaliyega.|फ़्लैशलाइट चालू है। किसी की आँखों में मत डालिएगा।
        Aapki location Al Barsha hai.|आपकी लोकेशन अल बरशा है।
        Paanch baje yaad dila doonga.|पाँच बजे याद दिला दूँगा।
        Kya aap sach mein raat ke do baje yeh karna chahte hain?|क्या आप सच में रात के दो बजे यह करना चाहते हैं?
        Oh ho, yeh toh plan ke hisaab se nahin hua.|ओ हो, यह तो प्लान के हिसाब से नहीं हुआ।
        Hmm, dilchasp. Aur bataiye.|हम्म, दिलचस्प। और बताइए।
        Bilkul nahin. Accha, shayad. Dekhta hoon.|बिल्कुल नहीं। अच्छा, शायद। देखता हूँ।
        Main aapka apna assistant hoon, Rex ne banaya hai mujhe.|मैं आपका अपना असिस्टेंट हूँ, रेक्स ने बनाया है मुझे।
        Har bada idea pehle thoda pagal lagta hai.|हर बड़ा आइडिया पहले थोड़ा पागल लगता है।
        Mubarak ho! Yeh toh shandaar khabar hai.|मुबारक हो! यह तो शानदार ख़बर है।
        Koi baat nahin, aap apna waqt lijiye.|कोई बात नहीं, आप अपना वक़्त लीजिए।
        Kya yeh zaroori hai, ya subah tak ruk sakta hai?|क्या यह ज़रूरी है, या सुबह तक रुक सकता है?
        Pehle taareekh dekhiye, phir sign kijiye, phir wapas bhejiye.|पहले तारीख़ देखिए, फिर साइन कीजिए, फिर वापस भेजिए।
        Sach kahoon toh sir, aap zyada soch rahe hain.|सच कहूँ तो सर, आप ज़्यादा सोच रहे हैं।
        Aapki ungliyan aaj mere processor se tez hain.|आपकी उँगलियाँ आज मेरे प्रोसेसर से तेज़ हैं।
        Naya chat shuru kar raha hoon. Purana menu mein save hai.|नया चैट शुरू कर रहा हूँ। पुराना मेनू में सेव है।
        Zabardast. Chaliye kaam pe lagte hain.|ज़बरदस्त। चलिए काम पे लगते हैं।
        Beep ke baad boliye.|बीप के बाद बोलिए।
        Sun raha hoon, boliye.|सुन रहा हूँ, बोलिए।
        Maine suna: ghar call karo. Call kar raha hoon.|मैंने सुना: घर कॉल करो। कॉल कर रहा हूँ।
        Volume poora hai. Padosi aapko dhanyavaad kehte hain.|वॉल्यूम पूरा है। पड़ोसी आपको धन्यवाद कहते हैं।
        Gyaarah baj kar pandrah minute ho gaye hain.|ग्यारह बज कर पंद्रह मिनट हो गए हैं।
        Dono phone jude hue hain aur surakshit hain.|दोनों फ़ोन जुड़े हुए हैं और सुरक्षित हैं।
        Main doosre apps ke button nahin daba sakta, par khol zaroor sakta hoon.|मैं दूसरे ऐप्स के बटन नहीं दबा सकता, पर खोल ज़रूर सकता हूँ।
        Us company ko paanch saal ke experience wale engineer chahiye.|उस कंपनी को पाँच साल के एक्सपीरियंस वाले इंजीनियर चाहिए।
        Aapki salary slip download ke liye taiyaar hai.|आपकी सैलरी स्लिप डाउनलोड के लिए तैयार है।
        Ek mazedaar baat sunenge? Octopus ke teen dil hote hain.|एक मज़ेदार बात सुनेंगे? ऑक्टोपस के तीन दिल होते हैं।
        Suraj chhe baj kar das minute par nikalta hai.|सूरज छह बज कर दस मिनट पर निकलता है।
        Mujhe aapki file mil gayi. Downloads mein chhupi thi.|मुझे आपकी फ़ाइल मिल गई। डाउनलोड्स में छुपी थी।
        Chaliye, ek baar aur koshish karte hain, thoda dheere.|चलिए, एक बार और कोशिश करते हैं, थोड़ा धीरे।
        Maaf kijiye, yeh abhi meri kshamta se bahar hai.|माफ़ कीजिए, यह अभी मेरी क्षमता से बाहर है।
        Charging poori ho gayi. Main poori taakat se taiyaar hoon.|चार्जिंग पूरी हो गई। मैं पूरी ताक़त से तैयार हूँ।
        Kya main iska Hindi mein anuvaad kar doon?|क्या मैं इसका हिंदी में अनुवाद कर दूँ?
        Aapka parcel aaj subah darwaaze par aa gaya.|आपका पार्सल आज सुबह दरवाज़े पर आ गया।
        Main das minute pehle nikalne ki salaah doonga.|मैं दस मिनट पहले निकलने की सलाह दूँगा।
        Yeh number aapke contacts mein nahin hai. Save kar doon?|यह नंबर आपके कॉन्टैक्ट्स में नहीं है। सेव कर दूँ?
        Aaj aapka screen time chaar ghante hai. Bas bata raha hoon.|आज आपका स्क्रीन टाइम चार घंटे है। बस बता रहा हूँ।
        Smart dimaag load ho raha hai. Ek minute lagega.|स्मार्ट दिमाग़ लोड हो रहा है। एक मिनट लगेगा।
        Ho gaya. Aur kuch seva?|हो गया। और कुछ सेवा?
        Duniya badi hai sir, par main raasta dhoondh doonga.|दुनिया बड़ी है सर, पर मैं रास्ता ढूँढ दूँगा।
        Gaana chala doon kaam ke saath?|गाना चला दूँ काम के साथ?
        Kal subah aath baje baarish hogi.|कल सुबह आठ बजे बारिश होगी।
        Aapke phone mein chaalees GB jagah baaki hai.|आपके फ़ोन में चालीस जीबी जगह बाक़ी है।
        Maaf kijiye sir, par yeh email thoda narm ho sakta hai.|माफ़ कीजिए सर, पर यह ईमेल थोड़ा नर्म हो सकता है।
        Theek hai, chhota rakhta hoon.|ठीक है, छोटा रखता हूँ।
        Kya main is document ka saar bataun?|क्या मैं इस डॉक्यूमेंट का सार बताऊँ?
        Mere hisaab se aapko aur neend chahiye.|मेरे हिसाब से आपको और नींद चाहिए।
        Main khud ko mute kar raha hoon.|मैं ख़ुद को म्यूट कर रहा हूँ।
        Flight, hotel, ya dono dhoondhoon?|फ़्लाइट, होटल, या दोनों ढूँढूँ?
        Keemat pichhle mahine se barah percent badh gayi hai.|क़ीमत पिछले महीने से बारह परसेंट बढ़ गई है।
        Aaj aap chhe hazaar kadam chale. Bura nahin.|आज आप छह हज़ार क़दम चले। बुरा नहीं।
        Main har din thoda thoda seekhta hoon.|मैं हर दिन थोड़ा थोड़ा सीखता हूँ।
        Is kaam mein bas ek pal lagega.|इस काम में बस एक पल लगेगा।
        Aap kaise hain? Bahut dinon baad baat ho rahi hai.|आप कैसे हैं? बहुत दिनों बाद बात हो रही है।
        Kya aapne khana khaya?|क्या आपने खाना खाया?
        Sir, dhyan se gaadi chalaiye.|सर, ध्यान से गाड़ी चलाइए।
        Mujhe aap par poora bharosa hai.|मुझे आप पर पूरा भरोसा है।
        Yeh raaz main kisi ko nahin bataunga.|यह राज़ मैं किसी को नहीं बताऊँगा।
        Aaj ki taareekh sattaees September hai.|आज की तारीख़ सत्ताईस सितंबर है।
        Zindagi mein thoda mazaak bhi zaroori hai.|ज़िंदगी में थोड़ा मज़ाक भी ज़रूरी है।
        Aapne jo kaha, woh maine note kar liya.|आपने जो कहा, वह मैंने नोट कर लिया।
        Kripya thoda intezaar kijiye.|कृपया थोड़ा इंतज़ार कीजिए।
        Yeh khabar sunkar dil khush ho gaya.|यह ख़बर सुनकर दिल ख़ुश हो गया।
        Main aapki har baat dhyan se sunta hoon.|मैं आपकी हर बात ध्यान से सुनता हूँ।
        Iron Man ka Jarvis bhi itna samajhdaar nahin tha.|आयरन मैन का जार्विस भी इतना समझदार नहीं था।
        Pareshan mat hoiye, hal nikal aayega.|परेशान मत होइए, हल निकल आएगा।
        Kaam khatam, ab thoda aaram.|काम ख़त्म, अब थोड़ा आराम।
        Aapka din shubh ho, sir.|आपका दिन शुभ हो, सर।
    """.trimIndent().lines().filter { it.isNotBlank() }.map {
        val (show, say) = it.split("|", limit = 2)
        Line(show.trim(), say.trim())
    }
}

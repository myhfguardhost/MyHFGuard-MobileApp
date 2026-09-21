package com.vitalink.app.ui.screens

import com.vitalink.app.util.AppLanguage

internal data class EducationTopicGroup(val title: String, val videoKeys: List<String>)
internal data class EducationModuleGroup(val id: String, val title: String, val description: String, val topics: List<EducationTopicGroup>)

// Mapping follows the supplied Learning modules and videos link document.
internal fun educationModuleGroups(): List<EducationModuleGroup> = listOf(
    EducationModuleGroup("understanding", AppLanguage.text("Understanding Heart Failure", "Memahami Kegagalan Jantung", "认识心力衰竭", "இதய செயலிழப்பைப் புரிந்துகொள்ளுதல்"), AppLanguage.text("Learn what heart failure is, how it can feel, and the different types.", "Ketahui maksud kegagalan jantung, gejala dan jenisnya.", "了解什么是心力衰竭、相关感受和不同类型。", "இதய செயலிழப்பு, அதன் அறிகுறிகள் மற்றும் வகைகளை அறியுங்கள்."), listOf(
        EducationTopicGroup(AppLanguage.text("What is heart failure?", "Apakah kegagalan jantung?", "什么是心力衰竭？", "இதய செயலிழப்பு என்றால் என்ன?"), listOf("youtube-J_sLTDGE70w")),
        EducationTopicGroup(AppLanguage.text("How does heart failure make you feel?", "Apakah gejala kegagalan jantung?", "心力衰竭会带来什么感受？", "இதய செயலிழப்பு எப்படி உணர வைக்கும்?"), listOf("youtube-FBD7M9GxGCQ")),
        EducationTopicGroup(AppLanguage.text("Different types of heart failure", "Jenis kegagalan jantung", "心力衰竭的不同类型", "இதய செயலிழப்பின் வகைகள்"), listOf("youtube-1-BDMXOWMD8")),
    )),
    EducationModuleGroup("medicines", AppLanguage.text("Managing Heart Failure Medicines", "Mengurus Ubat Kegagalan Jantung", "管理心力衰竭药物", "இதய செயலிழப்பு மருந்துகளை நிர்வகித்தல்"), AppLanguage.text("Understand your medicines and how different medicine groups work.", "Fahami ubat anda dan cara kumpulan ubat berfungsi.", "认识您的药物以及不同类别药物的作用。", "உங்கள் மருந்துகளையும் அவை செயல்படும் விதத்தையும் அறியுங்கள்."), listOf(
        EducationTopicGroup(AppLanguage.text("Medicines for heart failure", "Ubat untuk kegagalan jantung", "心力衰竭用药", "இதய செயலிழப்புக்கான மருந்துகள்"), listOf("youtube-8O4VExOSXcc", "youtube-WOWLsHldqwM", "youtube-xIlaQuRaZmk", "youtube-uiYJKvwVhEU", "youtube-qK3HFqk8ubk", "youtube-o6Oi_N5jotg")),
        EducationTopicGroup(AppLanguage.text("How to titrate your medicines", "Pelarasan dos ubat", "如何调整药物剂量", "மருந்து அளவைச் சரிசெய்தல்"), listOf()),
    )),
    EducationModuleGroup("warning", AppLanguage.text("Warning Signs", "Tanda Amaran", "警示信号", "எச்சரிக்கை அறிகுறிகள்"), AppLanguage.text("Recognise warning signs and learn what to do when you feel unwell.", "Kenali tanda amaran dan tindakan apabila tidak sihat.", "识别警示信号，了解不适时应采取的行动。", "எச்சரிக்கை அறிகுறிகளையும் உடல்நலம் குன்றும்போது செய்ய வேண்டியவற்றையும் அறியுங்கள்."), listOf(
        EducationTopicGroup(AppLanguage.text("Signs, symptoms and feeling unwell", "Tanda, gejala dan rasa tidak sihat", "症状与身体不适", "அறிகுறிகளும் உடல்நலக் குறைவும்"), listOf("youtube-52eMwhf8UfI", "youtube-WPOAhPoLbfA")),
    )),
    EducationModuleGroup("self-care", AppLanguage.text("What You Can Do", "Apa Yang Anda Boleh Lakukan", "您可以做些什么", "நீங்கள் செய்யக்கூடியவை"), AppLanguage.text("Explore daily self-care, blood pressure, diabetes, cholesterol and food choices.", "Terokai penjagaan diri, tekanan darah, diabetes, kolesterol dan pilihan makanan.", "学习日常自我护理、血压、糖尿病、胆固醇及饮食选择。", "தினசரி பராமரிப்பு, இரத்த அழுத்தம், நீரிழிவு, கொழுப்பு மற்றும் உணவுத் தேர்வுகளை அறியுங்கள்."), listOf(
        EducationTopicGroup(AppLanguage.text("Introduction to self-care", "Pengenalan penjagaan diri", "自我护理入门", "சுய பராமரிப்பு அறிமுகம்"), listOf("youtube-LU5k5zibE_g")),
        EducationTopicGroup(AppLanguage.text("How to manage your water restriction", "Mengurus had pengambilan air", "如何管理限水要求", "நீர் கட்டுப்பாட்டை நிர்வகித்தல்"), listOf()),
        EducationTopicGroup(AppLanguage.text("How to handle high blood pressure", "Mengurus tekanan darah tinggi", "如何管理高血压", "உயர் இரத்த அழுத்தத்தை நிர்வகித்தல்"), listOf("youtube-uM8yQNZ0x10", "youtube-4YNdp3pRjig", "youtube-yVFzSmG6ZB0", "youtube-wKCa9g0ob7k", "youtube-sUz-MxgnAxY", "youtube--64U9tUQA0A", "youtube-t1EnYhYDlJA")),
        EducationTopicGroup(AppLanguage.text("How to handle diabetes", "Mengurus diabetes", "如何管理糖尿病", "நீரிழிவை நிர்வகித்தல்"), listOf("youtube-xWmiRNfnJ4E", "youtube-oDOVXww7sSE", "youtube-gbfAXCuoOSk", "youtube-Gzz1J5FhHbU", "youtube-hTX0iGAAwWY")),
        EducationTopicGroup(AppLanguage.text("How to handle high cholesterol", "Mengurus kolesterol tinggi", "如何管理高胆固醇", "உயர் கொழுப்பை நிர்வகித்தல்"), listOf("youtube-Gb6pI2Grec4", "youtube-UaolDzxn-vE", "youtube-ZccHstNhKzU", "youtube-yAuSs-4hXa4", "youtube-RnF3j-IvhQc", "youtube-HPk2vM6CInM", "youtube-PCgB2mCFVT0", "youtube-o5aof7UI3yg")),
    )),
    EducationModuleGroup("living", AppLanguage.text("Living with Heart Failure", "Hidup dengan Kegagalan Jantung", "与心力衰竭共处", "இதய செயலிழப்புடன் வாழ்தல்"), AppLanguage.text("Learn about activity, emotional wellbeing and everyday life with heart failure.", "Ketahui aktiviti, kesejahteraan emosi dan kehidupan harian dengan kegagalan jantung.", "了解心力衰竭患者的运动、情绪健康和日常生活。", "இதய செயலிழப்புடன் உடற்பயிற்சி, உணர்ச்சி நலம் மற்றும் அன்றாட வாழ்க்கையை அறியுங்கள்."), listOf(
        EducationTopicGroup(AppLanguage.text("Stop smoking", "Berhenti merokok", "戒烟", "புகைப்பதை நிறுத்துதல்"), listOf()),
        EducationTopicGroup(AppLanguage.text("Weight control", "Kawalan berat badan", "体重管理", "எடை கட்டுப்பாடு"), listOf()),
        EducationTopicGroup(AppLanguage.text("Physical activity and exercise", "Aktiviti fizikal dan senaman", "身体活动与运动", "உடல் செயல்பாடும் உடற்பயிற்சியும்"), listOf("youtube-LU5k5zibE_g", "youtube-klZwKgXnzSI", "youtube-wWGulLAa0O0", "youtube-k60x24nN9CM", "youtube--JsuNKbAAkU", "youtube-fgKHFLe654U")),
        EducationTopicGroup(AppLanguage.text("Travelling & vaccinations", "Perjalanan dan vaksinasi", "旅行与疫苗接种", "பயணமும் தடுப்பூசிகளும்"), listOf()),
        EducationTopicGroup(AppLanguage.text("Your emotions", "Emosi anda", "您的情绪", "உங்கள் உணர்வுகள்"), listOf("youtube-cBRvo0284cg", "youtube-s0f-TtfrMRk", "youtube-p-SydwbwpwM", "youtube-DsK_gbYSSuo")),
        EducationTopicGroup(AppLanguage.text("Support group", "Kumpulan sokongan", "互助小组", "ஆதரவுக் குழு"), listOf()),
    )),
)

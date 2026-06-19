// AI 意图识别测试 v2 - 模拟 Kotlin AIIntentParser 的实际解析逻辑

const foodNutrition = {
    '米饭':[28,116,70],'面条':[25,110,60],'馒头':[45,220,85],'荞麦馒头':[45,220,65],
    '苏打饼干':[72,440,70],'饼干':[60,400,70],'鸡蛋':[1,70,30],'香椿炒鸡蛋':[5,180,40],
    '小油菜':[2,20,20],'油菜':[2,20,20],'炸鸡腿':[25,280,60],'炸鸡':[25,280,60],
    '葱爆羊肉':[5,200,30],'羊肉':[0,200,30],'虾仁':[1,90,40],'虾':[1,90,40],
    '全麦面包':[41,250,50],'面包':[50,280,70],'牛奶':[5,65,30],'水果':[13,50,40],'苹果':[14,52,36]
};

// ============ 中文数字 → 数字 ============
function chineseNumToDouble(s) {
    if (!s) return null;
    const pureDigits = s.replace(/[.,点]/g, ".");
    const num = parseFloat(pureDigits);
    if (!isNaN(num)) return num;

    let result = 0;
    let section = 0;
    let found = false;

    for (const c of s) {
        switch (c) {
            case '零': section = 0; break;
            case '一': section = 1; found = true; break;
            case '二': section = 2; found = true; break;
            case '三': section = 3; found = true; break;
            case '四': section = 4; found = true; break;
            case '五': section = 5; found = true; break;
            case '六': section = 6; found = true; break;
            case '七': section = 7; found = true; break;
            case '八': section = 8; found = true; break;
            case '九': section = 9; found = true; break;
            case '十':
                if (result === 0 && section === 0) section = 10;
                else section += 10;
                found = true;
                break;
            case '百': result += section * 100; section = 0; break;
            case '千': result += section * 1000; section = 0; break;
            case '万': result = (result + section) * 10000; section = 0; break;
            case '半': return 0.5;
        }
    }
    result += section;
    return found ? result : null;
}

// 测试中文数字
console.log('=== 中文数字测试 ===');
console.log('"十" →', chineseNumToDouble('十'), '(期望 10)');
console.log('"十五" →', chineseNumToDouble('十五'), '(期望 15)');
console.log('"一百二十" →', chineseNumToDouble('一百二十'), '(期望 120)');
console.log('"半" →', chineseNumToDouble('半'), '(期望 0.5)');

// ============ 解析器 (模拟 Kotlin AIIntentParser) ============
const numPattern = '[\\d零一二三四五六七八九十百千万半]+(?:[.,点]\\d+)?';

const glucoseRegex = new RegExp(`血糖\\s*(${numPattern})(?:\\s*(mmol/L|mmol))?(?:\\s*(餐前|餐后|空腹|睡前))?`, 'g');
const insulinRegex = new RegExp(`打了?\\s*(${numPattern})\\s*个?\\s*单位\\s*[的]?\\s*(长效|速效|短效|预混|基础|基础胰岛素|胰岛素)?`, 'g');
const exerciseRegex = new RegExp(`(跑步|快走|散步了?|走路|骑车|骑行|游泳|健身|力量|瑜伽|跳绳|爬山|打球)\\s*(${numPattern})\\s*(分钟|min|小时|h)?`, 'g');
const sleepRegex = new RegExp(`睡了?\\s*(${numPattern})\\s*(小时|h|hr)`);
const bpRegex = new RegExp(`(?:血压|高压|收缩压)\\s*(${numPattern})\\s*[/／]\\s*(${numPattern})`);
const weightRegex = new RegExp(`(?:体重|称了)\\s*(${numPattern})\\s*(?:kg|公斤|千克)?`);
const ketoneRegex = new RegExp(`(?:酮体|血酮)\\s*(${numPattern})`);
const medicationRegex = new RegExp(`(吃了?|服用了?|用了?)\\s*([一-龥]{2,10})\\s*(${numPattern})\\s*(mg|g|毫克|克)?`, 'g');
const symptomRegex = /(心慌|手抖|出汗|饥饿感|头晕|乏力|视物模糊|口渴|多尿|恶心|腹痛|呼吸急促|意识模糊)/g;

function parseIntents(input) {
    const intents = [];

    // CREATE: 血糖
    let m;
    while ((m = glucoseRegex.exec(input)) !== null) {
        const sceneMap = {'餐前':'before_meal','餐后':'after_meal','空腹':'fasting','睡前':'bedtime'};
        intents.push({type:'CREATE',target:'glucose',params:{value:chineseNumToDouble(m[1]),scene:sceneMap[m[3]]||'other'}});
    }

    // CREATE: 胰岛素
    while ((m = insulinRegex.exec(input)) !== null) {
        const typeMap = {'长效':'long','基础':'long','速效':'rapid','短效':'short','预混':'mixed'};
        intents.push({type:'CREATE',target:'insulin',params:{dose:chineseNumToDouble(m[1]),dose_type:typeMap[m[2]]||'rapid'}});
    }

    // CREATE: 运动
    while ((m = exerciseRegex.exec(input)) !== null) {
        const minutes = (m[3]||'').match(/小时|h/) ? chineseNumToDouble(m[2]) * 60 : chineseNumToDouble(m[2]);
        intents.push({type:'CREATE',target:'exercise',params:{duration_min:minutes}});
    }

    // CREATE: 睡眠
    m = input.match(sleepRegex);
    if (m) {
        intents.push({type:'CREATE',target:'sleep',params:{duration_minutes:Math.round(chineseNumToDouble(m[1])*60)}});
    }

    // CREATE: 血压
    m = input.match(bpRegex);
    if (m) {
        intents.push({type:'CREATE',target:'bp',params:{systolic:chineseNumToDouble(m[1]),diastolic:chineseNumToDouble(m[2])}});
    }

    // CREATE: 体重
    m = input.match(weightRegex);
    if (m) {
        intents.push({type:'CREATE',target:'weight',params:{weight_kg:chineseNumToDouble(m[1])}});
    }

    // CREATE: 酮体
    m = input.match(ketoneRegex);
    if (m) {
        intents.push({type:'CREATE',target:'ketone',params:{ketone_level:chineseNumToDouble(m[1])}});
    }

    // CREATE: 用药
    while ((m = medicationRegex.exec(input)) !== null) {
        const unit = m[4];
        const fullDose = unit ? m[3]+unit : m[3];
        intents.push({type:'CREATE',target:'medication',params:{medication_name:m[2],dose:fullDose}});
    }

    // CREATE: 症状
    const symptoms = [...new Set(input.match(symptomRegex) || [])];
    if (symptoms.length > 0) {
        intents.push({type:'CREATE',target:'symptom',params:{symptoms:symptoms.join(',')}});
    }

    // CREATE: 饮食 (复用 AiRecordHelper.localParse)
    const sentences = input.split(/[,，。.;；\n]/).map(s=>s.trim()).filter(s=>s);
    const foodKeys = Object.keys(foodNutrition).sort((a,b)=>b.length-a.length);
    const seenFoods = new Set();
    sentences.forEach(sent => {
        foodKeys.forEach(food => {
            if (sent.includes(food) && !seenFoods.has(food)) {
                seenFoods.add(food);
                let portion = 100;
                const gm = sent.match(/(\d+(?:\.\d+)?)\s*克/);
                if (gm) portion = parseFloat(gm[1]);
                else if (sent.includes('半个拳头')) portion = 35;
                else if (sent.includes('拳头大小')||sent.includes('拳头大')) portion = 70;
                else if (sent.includes('半拳头')) portion = 35;
                else if (sent.includes('半盘')) portion = 100;
                else if (sent.includes('一盘')) portion = 200;
                else if (sent.includes('几个')) portion = 50;
                else {
                    const cm = sent.match(/(\d+)\s*个/);
                    if (cm) {
                        const c = parseInt(cm[1]);
                        if (['鸡蛋','馒头','荞麦馒头','面包','苏打饼干','饼干','苹果'].includes(food)) portion = c*50;
                        else if (['虾仁','虾'].includes(food)) portion = c*30;
                        else if (['炸鸡腿','炸鸡'].includes(food)) portion = c*150;
                        else portion = c*100;
                    }
                }
                const [c100,cal100,gi] = foodNutrition[food];
                const carbs = portion * c100 / 100;
                intents.push({type:'CREATE',target:'meal',params:{food_name:food,carbs:carbs,portion:portion}});
            }
        });
    });

    // NAVIGATE
    const navKeywords = {'首页':'home','主页':'home','预测':'prediction','血糖预测':'prediction','设置':'settings',
        'AI助手':'chat','ai助手':'chat','AI 助手':'chat','饮食':'meal','饮食记录':'meal','胰岛素':'insulin','胰岛素记录':'insulin',
        '运动':'exercise','运动管理':'exercise','健康':'health','健康记录':'health','血糖':'glucose_list',
        '记录':'record','报告':'report'};
    const navRegex = /(?:打开|去|跳到|看看|进入|显示)\s*([\u4e00-\u9fa5a-zA-Z0-9]+(?:页|记录|助手)?)/g;
    while ((m = navRegex.exec(input)) !== null) {
        const key = m[1];
        const route = navKeywords[key] || Object.entries(navKeywords).find(([k])=>key.includes(k))?.[1];
        if (route) intents.push({type:'NAVIGATE',target:'page',params:{route:route}});
    }

    // CONFIGURE
    const configKeywords = {'目标范围':'target_range','血糖目标':'target_range','AI配置':'ai_config',
        '个人信息':'personal_info','通知监听':'notification','数据备份':'data_backup'};
    Object.entries(configKeywords).forEach(([key, action]) => {
        if (input.includes(key)) intents.push({type:'CONFIGURE',target:'settings',params:{action}});
    });

    // QUERY
    const queryRegex = /(今天|昨天|最近|本周|这周|上月|本月)?\s*(血糖|饮食|胰岛素|运动|睡眠|血压|体重|酮体|用药|症状)\s*(记录|数据|情况|趋势|怎么样|多少|平均|最高|最低|什么)?/g;
    while ((m = queryRegex.exec(input)) !== null) {
        const timeScope = m[1] || 'today';
        const targetMap = {'血糖':'glucose','饮食':'meal','胰岛素':'insulin','运动':'exercise','睡眠':'sleep',
            '血压':'bp','体重':'weight','酮体':'ketone','用药':'medication','症状':'symptom'};
        const target = targetMap[m[2]];
        if (target) intents.push({type:'READ',target:target,params:{time_scope:timeScope}});
    }

    // BULK_DELETE
    const deleteRegex = /(?:删除|清空|抹掉|丢掉)\s*(今天|昨天|最近|本周|所有|全部)?\s*(血糖|饮食|胰岛素|运动|睡眠|血压|体重|酮体|用药|症状)\s*(记录|数据)?/g;
    while ((m = deleteRegex.exec(input)) !== null) {
        const timeScope = m[1] || 'today';
        const targetMap = {'血糖':'glucose','饮食':'meal','胰岛素':'insulin','运动':'exercise','睡眠':'sleep',
            '血压':'bp','体重':'weight','酮体':'ketone','用药':'medication','症状':'symptom'};
        const target = targetMap[m[2]];
        if (target) intents.push({type:'BULK_DELETE',target:target,params:{time_scope:timeScope}});
    }

    return intents;
}

// ============ 测试 ============

console.log('\n=============================================');
console.log('  AI 助手权限意图识别测试 v2 (支持中文数字)');
console.log('=============================================');

// CREATE 测试
const createTests = [
    ["血糖 6.5", 'CREATE', 'glucose', (p) => p.value === 6.5],
    ["血糖6.5", 'CREATE', 'glucose', (p) => p.value === 6.5],
    ["血糖 6.5 mmol/L", 'CREATE', 'glucose', (p) => p.value === 6.5],
    ["血糖 7.2 餐前", 'CREATE', 'glucose', (p) => p.value === 7.2 && p.scene === 'before_meal'],
    ["血糖 9.0 空腹", 'CREATE', 'glucose', (p) => p.scene === 'fasting'],
    ["血糖 8.0 餐后", 'CREATE', 'glucose', (p) => p.scene === 'after_meal'],
    ["打了八个单位的速效", 'CREATE', 'insulin', (p) => p.dose === 8 && p.dose_type === 'rapid'],
    ["打了十个单位的长效", 'CREATE', 'insulin', (p) => p.dose === 10 && p.dose_type === 'long'],
    ["打了 4 个单位", 'CREATE', 'insulin', (p) => p.dose === 4],
    ["跑步30分钟", 'CREATE', 'exercise', (p) => p.duration_min === 30],
    ["散步了1小时", 'CREATE', 'exercise', (p) => p.duration_min === 60],
    ["走路20分钟", 'CREATE', 'exercise', (p) => p.duration_min === 20],
    ["骑车40min", 'CREATE', 'exercise', (p) => p.duration_min === 40],
    ["睡了 8 小时", 'CREATE', 'sleep', (p) => p.duration_minutes === 480],
    ["睡了7.5小时", 'CREATE', 'sleep', (p) => p.duration_minutes === 450],
    ["血压 120/80", 'CREATE', 'bp', (p) => p.systolic === 120 && p.diastolic === 80],
    ["血压135/85", 'CREATE', 'bp', (p) => p.systolic === 135 && p.diastolic === 85],
    ["体重 70 kg", 'CREATE', 'weight', (p) => p.weight_kg === 70],
    ["体重 65.5", 'CREATE', 'weight', (p) => p.weight_kg === 65.5],
    ["酮体 0.5", 'CREATE', 'ketone', (p) => p.ketone_level === 0.5],
    ["吃了二甲双胍 500mg", 'CREATE', 'medication', (p) => p.medication_name === '二甲双胍'],
    ["服用了格列美脲 5mg", 'CREATE', 'medication', (p) => p.medication_name === '格列美脲'],
    ["心慌头晕", 'CREATE', 'symptom', (p) => p.symptoms.includes('心慌') && p.symptoms.includes('头晕')],
    ["我手抖出汗", 'CREATE', 'symptom', (p) => p.symptoms.includes('手抖') && p.symptoms.includes('出汗')],
    // ★ 中文数字测试
    ["血糖七点五", 'CREATE', 'glucose', (p) => p.value === 7.5],
    ["打了十个单位的长效", 'CREATE', 'insulin', (p) => p.dose === 10 && p.dose_type === 'long'],
    ["睡了八小时", 'CREATE', 'sleep', (p) => p.duration_minutes === 480],
];

const navigateTests = [
    ["打开首页", 'NAVIGATE', 'home'],
    ["去预测", 'NAVIGATE', 'prediction'],
    ["打开 AI 助手", 'NAVIGATE', 'chat'],
    ["去设置", 'NAVIGATE', 'settings'],
    ["看看饮食", 'NAVIGATE', 'meal'],
    ["进入胰岛素", 'NAVIGATE', 'insulin'],
    ["看看运动", 'NAVIGATE', 'exercise'],
    ["显示健康", 'NAVIGATE', 'health'],
    ["去报告", 'NAVIGATE', 'report'],
    ["打开记录页", 'NAVIGATE', 'record'],
];

const configureTests = [
    ["目标范围", 'CONFIGURE', 'settings'],
    ["AI 配置", 'CONFIGURE', 'settings'],
    ["个人信息", 'CONFIGURE', 'settings'],
    ["通知", 'CONFIGURE', 'settings'],
    ["通知监听", 'CONFIGURE', 'settings'],
    ["API Key", 'CONFIGURE', 'settings'],
    ["数据备份", 'CONFIGURE', 'settings'],
];

const queryTests = [
    ["今天血糖怎么样", 'READ', 'glucose'],
    ["今天吃了什么", 'READ', 'meal'],
    ["本周胰岛素", 'READ', 'insulin'],
    ["最近睡眠", 'READ', 'sleep'],
    ["昨天体重", 'READ', 'weight'],
    ["今天血压", 'READ', 'bp'],
    ["本周运动", 'READ', 'exercise'],
    ["最近症状", 'READ', 'symptom'],
    ["本月用药", 'READ', 'medication'],
    ["最近酮体", 'READ', 'ketone'],
];

const bulkDeleteTests = [
    ["删除今天血糖", 'BULK_DELETE', 'glucose'],
    ["清空昨天饮食", 'BULK_DELETE', 'meal'],
    ["抹掉今天胰岛素", 'BULK_DELETE', 'insulin'],
    ["删除所有症状", 'BULK_DELETE', 'symptom'],
    ["清空全部体重", 'BULK_DELETE', 'weight'],
];

function runTests(name, tests) {
    let passed = 0;
    const failed = [];
    tests.forEach(([input, expectedType, expectedTarget, validator]) => {
        const intents = parseIntents(input);
        let success = false;
        if (expectedType === 'CREATE') {
            const found = intents.find(i => i.type === 'CREATE' && i.target === expectedTarget);
            if (found && (!validator || validator(found.params))) success = true;
        } else if (expectedType === 'NAVIGATE') {
            const found = intents.find(i => i.type === 'NAVIGATE');
            if (found && found.params.route === expectedTarget) success = true;
        } else if (expectedType === 'CONFIGURE') {
            const found = intents.find(i => i.type === 'CONFIGURE');
            if (found) success = true;
        } else if (expectedType === 'READ') {
            const found = intents.find(i => i.type === 'READ' && i.target === expectedTarget);
            if (found) success = true;
        } else if (expectedType === 'BULK_DELETE') {
            const found = intents.find(i => i.type === 'BULK_DELETE' && i.target === expectedTarget);
            if (found) success = true;
        }
        if (success) passed++;
        else failed.push([input, expectedType, expectedTarget, intents.map(i => `${i.type}:${i.target}`)]);
    });
    const accuracy = (passed / tests.length * 100).toFixed(1);
    console.log(`\n=== ${name} ===`);
    console.log(`  通过: ${passed}/${tests.length} (${accuracy}%)`);
    if (failed.length > 0) {
        console.log(`  失败用例:`);
        failed.forEach(([input, type, target, got]) => {
            console.log(`    [${input}] 期望 ${type}:${target}, 实际 ${got.join(',') || '(无)'}`);
        });
    }
    return { passed, total: tests.length, accuracy: parseFloat(accuracy) };
}

const r1 = runTests('CREATE - 血糖/胰岛素/运动/睡眠/血压/体重/酮体/用药/症状', createTests);
const r2 = runTests('NAVIGATE - 跳转到页面', navigateTests);
const r3 = runTests('CONFIGURE - 设置项', configureTests);
const r4 = runTests('QUERY - 数据查询', queryTests);
const r5 = runTests('BULK_DELETE - 批量删除', bulkDeleteTests);

// 用户的核心测试用例
console.log('\n=============================================');
console.log('  用户的核心长描述测试');
console.log('=============================================');
const longInput = "今天早上零点，打了十个单位的长效，早饭在9点半吃的，打了八个单位的速效，吃的一个25克的苏打饼干，太平品牌的，半盘香椿炒鸡蛋，半盘小油菜，早餐后面还吃了个顿，加餐是12点半吃的，吃了一个炸鸡腿，但是把外面的那层酥皮给扒掉了，午饭前打了八个单位的速效是，午饭是13点吃的，吃了半盘葱爆羊肉，吃了几个虾仁，吃了一个荞麦面的馒头，大概半个拳头大小";

const longIntents = parseIntents(longInput);
console.log(`输入: ${longInput.slice(0, 80)}...`);
console.log(`\n识别到 ${longIntents.length} 条意图:`);
longIntents.forEach((i, idx) => {
    const params = Object.entries(i.params).map(([k,v]) => `${k}=${v}`).join(', ');
    console.log(`  ${idx+1}. ${i.type}:${i.target} (${params})`);
});

// 总分
const totalPassed = r1.passed + r2.passed + r3.passed + r4.passed + r5.passed;
const totalTests = r1.total + r2.total + r3.total + r4.total + r5.total;
const totalAcc = (totalPassed / totalTests * 100).toFixed(1);

console.log('\n=============================================');
console.log('  总分');
console.log('=============================================');
console.log(`  通过: ${totalPassed}/${totalTests} (${totalAcc}%)`);
console.log(`  长描述识别: ${longIntents.length} 条意图`);
# 阅读背单词（ReadVocab）

面向已有词汇基础的中文母语者：读你自己感兴趣的英文内容，App 只标出「对你来说是生词」的词，在上下文里学，再用间隔复习把它带回到之后的阅读中。

## MVP 功能

- **导入**：TXT（自动识别 UTF-8 / GBK，按 Chapter 标题分章）、EPUB、直接粘贴文章；首次启动自带一篇示例文章。
- **词汇量测试**：12 个词频段（COCA 词频前 3 万）各抽 6 词，估算词汇量，并据此决定阅读时哪些词默认视为已认识。
- **阅读器**：超出你水平的生词加下划线，学习中的词黄色高亮，熟悉的词浅绿；可一键只高亮学习中的词；可调字号。
- **点词查词**：自动词形还原（ran → run，studies → study），显示音标、ECDICT 释义、考试标签、TTS 发音、原句；可用 AI 给出「本句里的意思」、整句翻译和搭配。
- **长按句子**：AI 整句翻译 + 结构解析。
- **阅读中复习**：点过的词自动进入「学习中」（FSRS 调度）。之后在书里再遇到、读过去没点它，就算一次成功复习；读过 3 次都没点的生词自动记为「已认识」，词汇模型随阅读自我修正。
- **复习卡**：只出现到期了、最近又没在阅读中碰到的词；正面是你当初遇到它的原句挖空。
- **生词本**：按学习中 / 熟悉 / 掌握 / 已知 / 忽略分类，查看遇到过的所有句子。
- **新闻**：订阅 BBC、纽约客、NYT 等网站的 RSS，按时间列出最新标题；可以开关或删除订阅源，也能自己添加。
- **内置浏览器 + 一键导入**：在 App 里打开任意英文网页，点"导入阅读"，用 Mozilla Readability（Firefox 阅读模式的引擎）提取正文放进书架。需要订阅的网站在内置浏览器里登录一次即可。也可以在其他 App 里"分享"链接或一段文字到本 App。
- **难度匹配度**：每本书、每篇文章都标出"你认识其中百分之几的词"（按你的词汇量和生词表计算）：98% 以上轻松，95–98% 合适，90–95% 有挑战，90% 以下偏难。
- **今日面板**：书架顶部显示待复习、今天已复习、今天新加的词、今天阅读的词数和时长、连续学习天数。
- **学习统计**：未来 7 天每天要复习多少词、近 30 天的复习量和阅读量、生词表的记忆状态分布。
- **每日提醒**：可设时间。有到期的词就提醒复习；没有的话，当天还没读过才提醒。
- **备份与恢复**：把书籍、生词、复习记录和阅读设置打包成一个 zip 文件，换手机时恢复。备份不含 API Key。
- **AI 设置**：在 App 里填自己的 API Key。支持 Claude（官方 Anthropic Java SDK）和任意 OpenAI 兼容接口（OpenAI、DeepSeek、通义、Kimi 等，附预设）。Key 只存在本机。

## 项目结构

```
core/   纯 Kotlin（JVM）模块，可直接单测
  text/      分词、句子切分、词形还原
  srs/       FSRS-4.5 调度
  vocab/     词状态、高亮规则、词汇量估算
  ai/        AiProvider 抽象 + AnthropicProvider + OpenAiCompatibleProvider + AiTutor（提示词与 JSON 解析）
  importer/  TXT / EPUB / HTML 解析
app/    Android（Jetpack Compose + Room）
  data/      Room 实体与 DAO、词典仓库（assets/ecdict.db.gzip）、书籍与单词仓库、设置
  ui/        书架、阅读器、查词卡、复习、生词本、词汇量测试、设置
tools/build_dict.py  从 ECDICT 生成 app/src/main/assets/ecdict.db.gzip
```

新增 AI 能力（例如按词汇量改写文章、生成例句）：在 `core/ai/AiTutor.kt` 加一个方法即可，所有服务商自动可用；新增服务商：实现 `AiProvider` 接口并在 `AiProviders.create` 里注册。

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35）。

```bash
./gradlew :core:test :app:testDebugUnitTest   # 单元测试 + Robolectric 界面冒烟测试
./gradlew :app:assembleRelease                 # 产出 app/build/outputs/apk/release/app-release.apk
```

release 包目前用 debug 签名，方便直接安装；正式发布前请换成你自己的签名。

重新生成词典：

```bash
curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/lemma.en.txt
python3 tools/build_dict.py ecdict.csv lemma.en.txt app/src/main/assets/ecdict.db  # writes ecdict.db.gzipip
```

## 后续（V2 / V3）

网页链接导入、按生词率推荐内容、导出 Anki / CSV、学习统计、AI 分级改写、云同步。

## 正文提取的测试

`app/src/main/assets/readability/` 里是 Readability.js（Apache 2.0）和包装脚本 `extract.js`。`tools/extract-test.js` 用 Playwright 在真实 Chromium 里对 `app/src/test/resources/*.html` 运行同一个脚本，生成 `*.extracted.json`，单元测试再用这些输出验证导入逻辑：

```
node tools/extract-test.js .
```

## 许可

词典数据来自 [ECDICT](https://github.com/skywind3000/ECDICT)（MIT）。

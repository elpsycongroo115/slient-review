# V12 女仆像素动画替换记录

V12 动画由 0.1.1-dev 合入，当前版本继续使用相同素材。

采用用户最终确认的 V12 蓝发鲸尾女仆动画。原始角色由 imagegen 生成，后续以整数像素取样实现固定脚底的呼吸、衣料跟随和局部动作，没有逐帧重新绘制轮廓或进行平滑插值。

| 资源 | 状态 | 帧数 | 整图尺寸 |
| --- | --- | --- | --- |
| `pet/whale_idle.png` | 待机 | 20 | 2880×176 |
| `pet/whale_wave.png` | 挥手 | 20 | 2880×176 |
| `pet/whale_happy.png` | 开心 | 20 | 2880×176 |
| `pet/whale_sleep.png` | 睡眠 | 20 | 2880×176 |
| `pet/whale_blink.png` | 眨眼 | 20 | 2880×176 |

每帧 144×176，透明 RGBA。共用 3200 毫秒周期，20 帧依次停留：

```text
420, 70, 70, 140, 140, 90, 90, 350, 70, 90,
70, 300, 70, 70, 140, 140, 90, 90, 300, 400
```

V12 的裙摆实际内收进一步减轻，吸气时主要蕾丝边缘相对 V11 每侧少收 1 像素。整片裙身、围裙图案、中央与侧边蕾丝共同响应；保留上身、腰线、裙身、裙摆依次跟随，固定小微笑与每组动作的眨眼。鞋底、下腿、手部保持固定，胸口只保留小范围布料变化。

播放器使用单调时钟选择当前关键帧，重绘定时器不再把全部关键帧按 220 毫秒均匀播放。切换审查状态共用动画时间轴，绘制继续采用最近邻取样和整数目标坐标。

本次重新编译 Java 并构建插件安装包。插件名称 `Silent Review`、ID `dev.silentreview.idea` 及设置存储保持一致，通过更高版本号覆盖更新。

`pet/animation.json` 与 [artwork/animation.json](artwork/animation.json) 保存素材时序；[artwork/preview.gif](artwork/preview.gif) 是五种状态总览，[artwork/idle-details.gif](artwork/idle-details.gif) 是衣料细节。自动生成的验证报告不提交；安装包通过 GitHub Releases 独立发布。

原始大肥鱼的 MIT 许可仍保留于 `META-INF/licenses/whale-pet-MIT.txt`；该历史许可不表示新参考形象或生成素材采用同一许可。本项目不是 DeepSeek 或 JetBrains 官方产品。

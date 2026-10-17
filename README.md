# 编译法术（Spell Compiler）

通过数据包编辑、编译并同步 Iron's Spells 'n Spellbooks 的法术参数和计算公式。独立运行，模组 ID 为 `spell_compiler`。

支持本体法术以及可安全接入的附属法术。复杂计算入口保留原生行为并输出诊断；检测到公式覆盖会绕过第三方数值调用时，拒绝对应配置。

## 环境

- Minecraft 1.20.1、Java 17、Gradle 8.5。
- 已验证 Forge 47.4.10、Iron's Spells 'n Spellbooks 1.20.1-3.16.1。
- 运行时还需 Curios、GeckoLib、playeranimator 和 irons_lib，版本见 `gradle.properties`。开发运行所需的后面三项由 Gradle 下载。

## 构建

先安装 Gradle 8.5，并将 Java 17 配置为 `JAVA_HOME`。本仓库使用系统 Gradle。

从模组发布页获取以下依赖，按指定名称放入工程根目录的 `libs/`。第三方依赖 JAR 不随本仓库分发。

| 本地文件名 | 模组与版本 | 本地验证使用的 SHA256 |
| --- | --- | --- |
| `curios-1.0.jar` | [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios) 5.14.1+1.20.1，Forge | `1E817919A35B37CF30524AAEC73F0CA5130452F23F168F844854DF282EB8E51F` |
| `ironsspells-3.16.1.jar` | [Iron's Spells 'n Spellbooks](https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks) 1.20.1-3.16.1，Forge | `B847B5A0D0B8D81EBF9F21F29FEE9AD180A235B8BF983EFA902EB67FC1485862` |

```shell
gradle jarJar
```

生成的模组位于 `build/libs/spell_compiler-0.1.0.jar`。Mixin 目标、公式目录和 refmap 由构建任务生成。

## 使用

1. 执行 `/spell_compiler export_spells`，需要权限等级 2。
2. 游戏目录下生成 `spell_compiler/` 数据包；导出保留已有文件。
3. 修改法术 JSON，将整个文件夹放入存档的 `datapacks/`，启用后执行 `/reload`。

有效的默认导出不会改变原生数值；移除配置后恢复原生行为。公式中的玩家属性只按表达式计算，常数伤害仍接受已接入的外部伤害词条，例如 `100 × 1.5 = 150`。

数据格式为 schema 4，网络协议为 10，联机双方应安装对应模组版本。玩家提示提供简体中文和英文翻译。

## 验证范围

测试、GameTest 和调试探针仅保留在本地，不纳入本仓库。

当前实现已通过 397 项单元测试、33 个原生 Forge 功能场景，以及真实词条桥接的 9 个场景、49 条严格观测。客户端主菜单检查覆盖 27 支附属法术接入和 21 项中英文文本解析。

上述检查使用 Forge 47.4.10；附属运行验证使用专用开发夹具。未完成用户完整整合包、全部附属版本、图形排版或 TCP 联机同步验收。

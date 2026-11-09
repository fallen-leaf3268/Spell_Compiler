# 编译法术（Spell Compiler）

适用于 Minecraft 1.20.1 Forge，通过数据包自定义 Iron's Spells 'n Spellbooks 的法术参数和计算公式。

- 修改伤害、耗魔、吟唱时间、冷却、范围和持续时间。
- 分别配置效果提供的属性、成长公式和加算／乘算方式。
- 支持部分附属法术。
- 支持配置导出、重载和服务端同步。
- 保留已接入的词条加成。

构建使用 JDK 17。按 [dependencies.json](dependencies.json) 准备 `libs` 中的依赖后，运行 `gradlew build`。

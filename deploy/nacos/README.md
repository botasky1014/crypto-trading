# Nacos 初始配置

目录名是命名空间 ID（design.md 5.10），文件名就是 Data ID，分组固定为 `DEFAULT_GROUP`。

`scripts/dev.sh up core` 会在 Nacos 就绪后：

1. 创建缺少的命名空间（目前只有 `dev`）；
2. 发布 Nacos 中**还不存在**的配置。已存在的配置不会被覆盖：之后的修改都在 Nacos 里进行（P6 起通过管理接口），这里的文件只作为首次启动的初始值。

想让文件里的新内容生效，可以在 Nacos 控制台删除对应配置后重新执行 `scripts/dev.sh up core`，或执行 `scripts/dev.sh reset` 清空全部数据。

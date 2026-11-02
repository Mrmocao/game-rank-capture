# 排名图形样本

`gravity-podium.json` 仅保存公开网页 NO.1、NO.2、NO.3 排名标记的三个 SVG src，不包含游戏或个人数据。
来源：https://rank.gravity-engine.com/wechat/day/ ，2026-11-02 HTTP采集；同日浏览器截图核对显示为 NO.1/NO.2/NO.3，微信、抖音共用这些标记。
单元测试把它们放入名称为 Fixture game 的合成HTML，不能作为真实榜单交付。图形哈希变化时解析器拒绝猜测，需重新目视验证再更新白名单。

# Apate 
> 阿帕忒（Apate）是“欺骗”的拟人化神，她是倪克斯的女儿，潘多拉宝盒中邪恶的精神之一。她在罗马神话中对应为福劳斯（Fraus）。

#### 介绍
基于spring cloud gateway的api加密网关

#### 软件架构
* spring boot
* spring cloud gateway

#### 安装教程

1.  xxxx
2.  xxxx
3.  xxxx

#### 使用说明

1.  修改配置文件，配置需要加密的api
2.  配置文件路径：src/main/resources/application.yml
```yaml
serviceHost:
  test1: http://jsonplaceholder.typicode.com
  xxx: http://xxxxxx.com
```
3. 客户端接口文档 
* 请求路径：`POST /`
* 请求头：`Content-Type: application/json`
* 请求参数：
1. `service`        后端服务标识，例如：host1、host2、host3
2. `path`           后端服务接口路径，例如：/helloword
3. `method`         请求后端服务的方法，例如：POST、GET
4. `contentType`    请求后端的请求参数类型，例如：application/json
5. `headers`        请求后端的请求头，例如：{Content-Type: application/json}
6. `params`         请求后端的请求参数，例如：{k1:v1,k2:v2}

##### 注意：客户端需要使用AES加密请求body，并使用AES解密响应body
```
AES加密模式：ECB
填充：pkcs5padding
密钥长度：128位
密钥：ABCD12345678EFGH
输出：base64
```
##### 示例：
##### body原文
```json
{
	"service": "test1",
	"path": "/todos/1",
	"method": "GET",
	"contentType": "",
	"headers": {},
	"params": {}
}
```
##### 加密后的请求
```
POST  HTTP/1.1
Host: localhost:10001
Content-Type: application/json
Cache-Control: no-cache

tsyBgbuTNO38aLCqEDyJtAUUOzzQEdgsyamq8wTRUyYC7MaRP8Y2T1/P0zbUPRb70Q/ExPabBw4xFTB8PCqH2N2/9aYiqy6gCpBD6Gdco8wtVz5Sncw6vosnFm1SA2PFS6S9XTljNayOzS24SpHZCSrsvvP5MyLUjQ6I2LgEXaM=
```
##### 响应原文
```
dOjGDpfy73qT8R4MTqmQcP1dMD4ng4REdlDoKiYMQ5oEKGWbKqbcywXIpTVhOi0ZSs2aZf3Xd++O+um9bSCfQOopyUU0wXGbuxJt8BnnkwGZOAfw4yxVd9bySIhDI97t
```
##### 解密后的响应
```json
{
  "userId": 1,
  "id": 1,
  "title": "delectus aut autem",
  "completed": false
}
```

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request


#### 特技

1.  加密请求url和请求参数
2.  加密相应数据
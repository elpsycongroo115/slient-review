package dev.silentreview.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class AiClient {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Response(String content, String model, JsonObject usage) {}
    public record Config(String endpoint, String model, String key) {}
    public static final class Failure extends IOException {
        private final boolean retryable;
        public Failure(String message,boolean retryable){super(message);this.retryable=retryable;}
        public boolean retryable(){return retryable;}
    }
    public Response review(Config config, String instructions, Review.Request request, BooleanSupplier canceled) throws IOException {
        if(canceled.getAsBoolean()) throw new CancellationException("检查已取消");
        URI endpoint = endpoint(config.endpoint());
        if (config.model().isBlank() || config.key().isBlank()) throw new IllegalArgumentException("请先配置模型和 API Key");
        if(config.key().chars().anyMatch(c->c<=32||c>=127))throw new IllegalArgumentException("API Key 包含无效字符，请重新填写");
        JsonObject body = new JsonObject();
        body.addProperty("model",config.model()); body.addProperty("stream",false); body.addProperty("max_tokens",8192);
        JsonObject format = new JsonObject(); format.addProperty("type","json_object"); body.add("response_format",format);
        JsonArray messages = new JsonArray();
        JsonObject system=new JsonObject(); system.addProperty("role","system"); system.addProperty("content",instructions); messages.add(system);
        JsonObject user=new JsonObject(); user.addProperty("role","user"); user.addProperty("content",request.json()); messages.add(user);
        body.add("messages",messages);
        HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(120))
            .header("Content-Type","application/json").header("Authorization","Bearer " + config.key())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        {
            HttpClient client=HTTP;
            CompletableFuture<HttpResponse<String>> future = client.sendAsync(http,HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response;
                while(true) {
                    if(canceled.getAsBoolean()) throw new CancellationException("检查已取消");
                    try { response=future.get(100,TimeUnit.MILLISECONDS); break; }
                    catch(TimeoutException ignored) { }
                }
                if(response.statusCode()!=200) throw new Failure(httpError(response.statusCode()),response.statusCode()==429||response.statusCode()>=500);
                if(response.body().length()>2_000_000) throw new IOException("AI 响应超过大小上限");
                try {
                    JsonObject data=StrictJson.parse(response.body()).getAsJsonObject();
                    JsonObject choice=data.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if(!"stop".equals(Rules.required(choice,"finish_reason"))) throw new IOException("AI 响应未完整结束，不能视为检查完成");
                    String content=Rules.required(choice.getAsJsonObject("message"),"content");
                    String model=data.has("model") ? data.get("model").getAsString() : config.model();
                    JsonObject usage=data.has("usage") && data.get("usage").isJsonObject() ? data.getAsJsonObject("usage") : null;
                    return new Response(content,model,usage);
                } catch (RuntimeException e) { throw new IOException("AI 返回空内容或无效的接口响应，检查未完成"); }
            } catch(ExecutionException e) { throw new Failure("AI 连接失败或超时，请检查接口设置与网络",true);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("检查已中断");
            } finally { if(!future.isDone()) future.cancel(true); }
        }
    }
    public static URI endpoint(String input) {
        URI uri;
        try { uri=URI.create(input.strip()); }
        catch(RuntimeException e){throw new IllegalArgumentException("AI 接口地址格式无效");}
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("AI 地址必须是完整接口地址，不包含账号、查询参数或片段");
        boolean loopback=java.util.Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
        if(!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("AI 接口使用 HTTPS；本机调试可使用 HTTP");
        String text=uri.toString().replaceAll("/+$","");
        return URI.create(text.endsWith("/chat/completions") ? text : text+"/chat/completions");
    }
    private static String httpError(int status) {
        return switch(status) {
            case 401,403 -> "AI 鉴权失败（HTTP " + status + "），请检查 API Key 与权限";
            case 402 -> "AI 账户余额不足（HTTP 402）";
            case 429 -> "AI 接口限流（HTTP 429），请稍后重试";
            default -> "AI 接口请求失败（HTTP " + status + "），检查未完成";
        };
    }
}

package dev.silentreview.core;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiClientTest {
    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> body=new AtomicReference<>(),auth=new AtomicReference<>();
    private int status=200;
    private String content;
    @BeforeEach void start()throws Exception{
        JsonObject envelope=new JsonObject(),choice=new JsonObject(),message=new JsonObject();
        message.addProperty("content",TestFixtures.response(true).toString());choice.add("message",message);choice.addProperty("finish_reason","stop");
        JsonArray choices=new JsonArray();choices.add(choice);envelope.add("choices",choices);envelope.addProperty("model","local-test");content=envelope.toString();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    private AiClient.Response call()throws IOException{return new AiClient().review(new AiClient.Config(endpoint,"local-test","test-local-key"),"Output JSON",TestFixtures.request(),()->false);}
    @Test void postsRulesAndValidatesResponseThroughWholeCorePipeline()throws Exception{
        var response=call();assertEquals("Bearer test-local-key",auth.get());var request=JsonParser.parseString(body.get()).getAsJsonObject();
        assertEquals("json_object",request.getAsJsonObject("response_format").get("type").getAsString());
        assertTrue(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("JAVA-001"));
        assertEquals(1,Review.validate(TestFixtures.request(),response.content()).findings().size());
    }
    @Test void rejectsTruncatedCompletion(){content=content.replace("\"finish_reason\":\"stop\"","\"finish_reason\":\"length\"");assertThrows(IOException.class,this::call);}
    @Test void rejectsEmptyContent(){content="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}";assertThrows(IOException.class,this::call);}
    @Test void doesNotExposeErrorResponseBodyOrToken(){status=401;content="secret request with test-local-key";var error=assertThrows(IOException.class,this::call);assertFalse(error.getMessage().contains("test-local-key"));assertTrue(error.getMessage().contains("401"));}
    @Test void preCanceledTaskDoesNotSendARequest(){assertThrows(CancellationException.class,()->new AiClient().review(new AiClient.Config(endpoint,"local-test","test-local-key"),"json",TestFixtures.request(),()->true));assertNull(body.get());}
    @Test void rejectsCredentialsInUrlAndInsecureRemoteEndpoint(){assertThrows(IllegalArgumentException.class,()->AiClient.endpoint("https://user:pass@example.com"));assertThrows(IllegalArgumentException.class,()->AiClient.endpoint("http://example.com"));assertEquals("https://api.deepseek.com/v1/chat/completions",AiClient.endpoint("https://api.deepseek.com/v1/").toString());}
    @Test void malformedCredentialIsNotEchoedInError(){var error=assertThrows(IllegalArgumentException.class,()->new AiClient().review(new AiClient.Config(endpoint,"local-test","private-secret\r\nvalue"),"json",TestFixtures.request(),()->false));assertFalse(error.getMessage().contains("private-secret"));assertNull(body.get());}
}

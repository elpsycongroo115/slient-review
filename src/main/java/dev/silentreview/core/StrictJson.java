package dev.silentreview.core;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;

/** Keep strict parsing across Gson versions shipped by different IDE builds. */
final class StrictJson {
    private StrictJson() {}
    static JsonElement parse(String text) {
        try(JsonReader reader=new JsonReader(new StringReader(text))){
            reader.setLenient(false);
            JsonElement result=value(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("JSON 末尾有额外内容");
            return result;
        }catch(IOException | NumberFormatException e){throw new IllegalArgumentException("JSON 语法错误："+e.getMessage(),e);}
    }
    private static JsonElement value(JsonReader reader,int depth)throws IOException {
        if(depth>100)throw new IOException("JSON 嵌套过深");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{
                JsonObject object=new JsonObject();reader.beginObject();
                while(reader.hasNext()){
                    String name=reader.nextName();if(object.has(name))throw new IOException("重复 JSON 字段："+name);
                    object.add(name,value(reader,depth+1));
                }
                reader.endObject();yield object;
            }
            case BEGIN_ARRAY->{JsonArray array=new JsonArray();reader.beginArray();while(reader.hasNext())array.add(value(reader,depth+1));reader.endArray();yield array;}
            case STRING->new JsonPrimitive(reader.nextString());
            case NUMBER->new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());
            case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("缺少 JSON 值");
        };
    }
}

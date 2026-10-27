package dev.silentreview.idea;

import dev.silentreview.core.Rules;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyStorageTest {
    private static final String URL="https://example.test";
    private static final class MemoryStore implements ApiKeyStorage.Store {
        final Map<String,String> values=new HashMap<>();
        int writes;boolean memoryOnly,dropWrites,failReads,failWrites;
        public String read(String endpoint){if(failReads)throw new IllegalStateException("provider details must stay private");return values.get(endpoint);}
        public void write(String endpoint,String value){
            writes++;if(failWrites)throw new IllegalStateException("provider details must stay private");
            if(!dropWrites)values.put(endpoint,value);
        }
        public boolean memoryOnly(){return memoryOnly;}
    }
    @Test void savesThenVerifiesWithoutReturningTheCredential(){
        var store=new MemoryStore();
        var status=ApiKeyStorage.saveVerified(store,URL,"test-credential");
        assertEquals(1,store.writes);assertTrue(status.present());assertFalse(status.memoryOnly());
        assertFalse(status.label().contains("test-credential"));assertFalse(status.toString().contains("test-credential"));
        assertTrue(ApiKeyStorage.inspect(store,URL).present());
    }
    @Test void emptyInputPreservesExistingKeyAndDoesNotWrite(){
        var store=new MemoryStore();store.values.put(URL,"existing-test-credential");
        assertTrue(ApiKeyStorage.saveVerified(store,URL,"").present());
        assertEquals(0,store.writes);assertEquals("existing-test-credential",store.values.get(URL));
    }
    @Test void whitespaceOnlyInputIsRejectedBeforeAnyCredentialWrite(){
        var store=new MemoryStore();store.values.put(URL,"existing-test-credential");
        for(String whitespace:new String[]{" ","\t\r\n","\u2003\u2003"}){
            var failure=assertThrows(IllegalArgumentException.class,()->ApiKeyStorage.saveVerified(store,URL,whitespace));
            assertTrue(failure.getMessage().contains("全部为空白"));
            assertEquals(0,store.writes);assertEquals("existing-test-credential",store.values.get(URL));
        }
        assertDoesNotThrow(()->ApiKeyStorage.validateReplacement(""));
        assertDoesNotThrow(()->ApiKeyStorage.validateReplacement("test-credential"));
    }
    @Test void failedWriteOrReadBackCannotReportSavedAndHidesProviderDetails(){
        var silent=new MemoryStore();silent.dropWrites=true;
        var failedWrite=new MemoryStore();failedWrite.failWrites=true;
        var failedRead=new MemoryStore();failedRead.failReads=true;
        for(var store:new MemoryStore[]{silent,failedWrite,failedRead}){
            var failure=assertThrows(IllegalStateException.class,()->ApiKeyStorage.saveVerified(store,URL,"test-credential"));
            assertTrue(failure.getMessage().contains("输入已保留"));
            assertFalse(failure.getMessage().contains("provider details"));assertNull(failure.getCause());
        }
    }
    @Test void stateExplainsMissingAndSessionOnlyCredentials(){
        var store=new MemoryStore();assertFalse(ApiKeyStorage.inspect(store,URL).present());
        store.memoryOnly=true;
        assertTrue(ApiKeyStorage.saveVerified(store,URL,"test-credential").label().contains("重启后需重填"));
        assertFalse(ApiKeyStorage.inspect(store,URL+"/").present());
    }
    @Test void oldCredentialNamespaceAndWhitespaceBehaviorStayCompatible(){
        String expected="SilentReview:project-location:"+Rules.sha256(URL).substring(0,16);
        assertEquals(expected,ApiKeyStorage.serviceName("project-location","  "+URL+"  "));
        assertNotEquals(expected,ApiKeyStorage.serviceName("project-location",URL+"/"));
        assertNotEquals(expected,ApiKeyStorage.serviceName("other-project",URL));
    }
    @Test void endpointResetAndDisposalInvalidateStaleAsyncResults(){
        var requests=new ApiKeyStorage.Requests();
        long first=requests.next(),second=requests.next();
        assertFalse(requests.isCurrent(first));assertTrue(requests.isCurrent(second));
        long sameEndpointAgain=requests.next();assertFalse(requests.isCurrent(second));
        assertTrue(requests.isCurrent(sameEndpointAgain));
        requests.next();assertFalse(requests.isCurrent(sameEndpointAgain));
    }
    @Test void newKeyInputTakesPrecedenceOverOldSavedStatusUntilCleared(){
        var requests=new ApiKeyStorage.Requests();long oldLookup=requests.next();
        assertTrue(requests.canDisplay(oldLookup,false));
        assertFalse(requests.canDisplay(oldLookup,true),"Even a current lookup cannot replace the unsaved-input label");
        requests.next();assertFalse(requests.canDisplay(oldLookup,false),"Typing invalidates the old lookup after input is cleared too");
        long clearedLookup=requests.next();assertTrue(requests.canDisplay(clearedLookup,false));
        assertTrue(ApiKeyStorage.PENDING_LABEL.contains("尚未保存"));
        assertTrue(ApiKeyStorage.PENDING_LABEL.contains("应用"));
    }
}

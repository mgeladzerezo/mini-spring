import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

/** Container health check: the JRE image ships no curl, so a single-file Java program does the request. */
public class Probe {
    public static void main(String[] args) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8202/api/accounts")).build();
        int status = HttpClient.newHttpClient().send(request, BodyHandlers.discarding()).statusCode();
        System.exit(status == 200 ? 0 : 1);
    }
}

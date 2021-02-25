package net.lightbody.bmp.filters;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import net.lightbody.bmp.util.BrowserMobHttpUtil;
import org.littleshoot.proxy.HttpFiltersAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * This filter captures requests from the client (headers and content).
 * <p/>
 * The filter can be used in one of three ways: (1) directly, by adding the filter to the filter chain; (2) by subclassing
 * the filter and overriding its filter methods; or (3) by invoking the filter directly from within another filter (see
 * {@link net.lightbody.bmp.filters.HarCaptureFilter} for an example of the latter).
 */
public class ClientRequestCaptureFilter extends HttpFiltersAdapter {
    /**
     * Populated by clientToProxyRequest() when processing the HttpRequest object. Unlike originalRequest,
     * this represents the "real" request that is being sent to the server, including headers.
     */
    private volatile HttpRequest httpRequest;

    /**
     * Populated by clientToProxyRequest() when processing the HttpContent objects. If the request is chunked,
     * it will be populated across multiple calls to clientToProxyRequest().
     */
    private final ByteArrayOutputStream requestContents = new ByteArrayOutputStream();

    /**
     * Populated when processing the LastHttpContent. If the response is compressed and decompression is requested,
     * this contains the entire decompressed request. Otherwise it contains the raw response.
     */
    private volatile byte[] fullRequestContents;

    /**
     * Populated when processing the LastHttpContent.
     */
    private volatile String contentEncoding;

    /**
     * Populated by clientToProxyRequest() when processing the LastHttpContent.
     */
    private volatile HttpHeaders trailingHeaders;

    public ClientRequestCaptureFilter(HttpRequest originalRequest) {
        super(originalRequest);
    }

    public ClientRequestCaptureFilter(HttpRequest originalRequest, ChannelHandlerContext ctx) {
        super(originalRequest, ctx);
    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            this.httpRequest = (HttpRequest) httpObject;
            captureContentEncoding(httpRequest);
        }

        if (httpObject instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) httpObject;

            storeRequestContent(httpContent);

            if (httpContent instanceof LastHttpContent) {
                LastHttpContent lastHttpContent = (LastHttpContent) httpContent;
                trailingHeaders = lastHttpContent .trailingHeaders();

                captureFullRequestContents();
            }
        }

        return null;
    }

    protected void captureFullRequestContents() {
        // start by setting fullResponseContent to the raw, (possibly) compressed byte stream. replace it
        // with the decompressed bytes if decompression is successful.
        fullRequestContents = getRawRequestContents();

        // if the content is compressed, we need to decompress it. but don't use
        // the netty HttpContentCompressor/Decompressor in the pipeline because we don't actually want it to
        // change the message sent to the client
        if (contentEncoding != null) {
            decompressContents();
        } else {
            // no compression
        }
    }

    protected void decompressContents() {
        if (contentEncoding.equals(HttpHeaders.Values.GZIP)) {
            try {
                fullRequestContents = BrowserMobHttpUtil.decompressContents(getRawRequestContents());
            } catch (RuntimeException e) {
                //log.warn("Failed to decompress response with encoding type " + contentEncoding + " when decoding request from " + originalRequest.getUri(), e);
            }
        } else {
            //log.warn("Cannot decode unsupported content encoding type {}", contentEncoding);
        }
    }

    protected void captureContentEncoding(HttpRequest httpRequest) {
        contentEncoding = HttpHeaders.getHeader(httpRequest, HttpHeaders.Names.CONTENT_ENCODING);
    }

    protected void storeRequestContent(HttpContent httpContent) {
        ByteBuf bufferedContent = httpContent.content();
        byte[] content = BrowserMobHttpUtil.extractReadableBytes(bufferedContent);

        try {
            requestContents.write(content);
        } catch (IOException e) {
            // can't happen
        }
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    public byte[] getFullRequestContents() {
        return fullRequestContents;
    }

    public byte[] getRawRequestContents() {
        return requestContents.toByteArray();
    }

    public HttpHeaders getTrailingHeaders() {
        return trailingHeaders;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }
}

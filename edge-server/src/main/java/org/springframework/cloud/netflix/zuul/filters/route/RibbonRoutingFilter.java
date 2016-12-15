package org.springframework.cloud.netflix.zuul.filters.route;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.cloud.netflix.ribbon.support.RibbonRequestCustomizer;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.MultiValueMap;

import com.netflix.client.ClientException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RibbonRoutingFilter extends ZuulFilter {

	private static final String ERROR_STATUS_CODE = "error.status_code";
	protected ProxyRequestHelper helper;
	protected RibbonCommandFactory<?> ribbonCommandFactory;
	protected List<RibbonRequestCustomizer> requestCustomizers;
	private boolean useServlet31 = true;

	public RibbonRoutingFilter(ProxyRequestHelper helper,
			RibbonCommandFactory<?> ribbonCommandFactory,
			List<RibbonRequestCustomizer> requestCustomizers) {
		this.helper = helper;
		this.ribbonCommandFactory = ribbonCommandFactory;
		this.requestCustomizers = requestCustomizers;
		// To support Servlet API 3.0.1 we need to check if getcontentLengthLong exists
		try {
			HttpServletResponse.class.getMethod("getContentLengthLong");
		} catch(NoSuchMethodException e) {
			useServlet31 = false;
		}
	}

	public RibbonRoutingFilter(RibbonCommandFactory<?> ribbonCommandFactory) {
		this(new ProxyRequestHelper(), ribbonCommandFactory, null);
	}

	@Override
	public String filterType() {
		return "route";
	}

	@Override
	public int filterOrder() {
		return 10;
	}

	@Override
	public boolean shouldFilter() {
		RequestContext ctx = RequestContext.getCurrentContext();
		return (ctx.getRouteHost() == null && ctx.get("serviceId") != null
				&& ctx.sendZuulResponse());
	}

	@Override
	public Object run() {
		RequestContext context = RequestContext.getCurrentContext();
		this.helper.addIgnoredHeaders();
		try {
			RibbonCommandContext commandContext = buildCommandContext(context);
			ClientHttpResponse response = forward(commandContext);
			setResponse(response);
			return response;
		}
		catch (ZuulException ex) {
			context.set(ERROR_STATUS_CODE, ex.nStatusCode);
			context.set("error.message", ex.errorCause);
			context.set("error.exception", ex);
		}
		catch (Exception ex) {
			context.set("error.status_code",
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			context.set("error.exception", ex);
		}
		return null;
	}

	protected RibbonCommandContext buildCommandContext(RequestContext context) {
		HttpServletRequest request = context.getRequest();
		Enumeration<String> parameters = request.getParameterNames();
		
		String version = (String)request.getParameter("version");

		log.error("version {}", version);
		
		MultiValueMap<String, String> headers = this.helper
				.buildZuulRequestHeaders(request);
		MultiValueMap<String, String> params = this.helper
				.buildZuulRequestQueryParams(request);
		String verb = getVerb(request);
		InputStream requestEntity = getRequestBody(request);
		if (request.getContentLength() < 0) {
			context.setChunkedRequestBody();
		}

        String serviceId = (String) context.get("serviceId") + "-" + version;
		
		log.error("serviceId {}", serviceId);
		Boolean retryable = (Boolean) context.get("retryable");

		String uri = this.helper.buildZuulRequestURI(request);

		// remove double slashes
		uri = uri.replace("//", "/");

		long contentLength = useServlet31 ? request.getContentLengthLong(): request.getContentLength();

		return new RibbonCommandContext(serviceId, verb, uri, retryable, headers, params,
				requestEntity, this.requestCustomizers, contentLength);
	}

	protected ClientHttpResponse forward(RibbonCommandContext context) throws Exception {
		Map<String, Object> info = this.helper.debug(context.getMethod(),
				context.getUri(), context.getHeaders(), context.getParams(),
				context.getRequestEntity());

		RibbonCommand command = this.ribbonCommandFactory.create(context);
		try {
			ClientHttpResponse response = command.execute();
			this.helper.appendDebug(info, response.getStatusCode().value(),
					response.getHeaders());
			return response;
		}
		catch (HystrixRuntimeException ex) {
			return handleException(info, ex);
		}

	}

	protected ClientHttpResponse handleException(Map<String, Object> info,
			HystrixRuntimeException ex) throws ZuulException {
		int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
		Throwable cause = ex;
		String message = ex.getFailureType().toString();

		ClientException clientException = findClientException(ex);
		if (clientException == null) {
			clientException = findClientException(ex.getFallbackException());
		}

		if (clientException != null) {
			if (clientException
					.getErrorType() == ClientException.ErrorType.SERVER_THROTTLED) {
				statusCode = HttpStatus.SERVICE_UNAVAILABLE.value();
			}
			cause = clientException;
			message = clientException.getErrorType().toString();
		}
		info.put("status", String.valueOf(statusCode));
		throw new ZuulException(cause, "Forwarding error", statusCode, message);
	}

	protected ClientException findClientException(Throwable t) {
		if (t == null) {
			return null;
		}
		if (t instanceof ClientException) {
			return (ClientException) t;
		}
		return findClientException(t.getCause());
	}

	protected InputStream getRequestBody(HttpServletRequest request) {
		InputStream requestEntity = null;
		try {
			requestEntity = (InputStream) RequestContext.getCurrentContext()
					.get("requestEntity");
			if (requestEntity == null) {
				requestEntity = request.getInputStream();
			}
		}
		catch (IOException ex) {
			log.error("Error during getRequestBody", ex);
		}
		return requestEntity;
	}

	protected String getVerb(HttpServletRequest request) {
		String method = request.getMethod();
		if (method == null) {
			return "GET";
		}
		return method;
	}

	protected void setResponse(ClientHttpResponse resp)
			throws ClientException, IOException {
		this.helper.setResponse(resp.getStatusCode().value(),
				resp.getBody() == null ? null : resp.getBody(), resp.getHeaders());
	}

}

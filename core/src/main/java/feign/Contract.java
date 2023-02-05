/*
 * Copyright 2012-2022 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.Request.HttpMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * Defines what annotations and values are valid on interfaces.
 */
public interface Contract {

  /**
   * Called to parse the methods in the class that are linked to HTTP requests.
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   */
  List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType);

  abstract class BaseContract implements Contract {

    /**
     * 将api客户端模板接口中的各个接口api方法解析出来
     * @param targetType {@link feign.Target#type() type} of the Feign interface.
     * @see #parseAndValidateMetadata(Class)
     */
    @Override
    public List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType) {
      checkState(targetType.getTypeParameters().length == 0, "Parameterized types unsupported: %s",
          targetType.getSimpleName());
      checkState(targetType.getInterfaces().length <= 1, "Only single inheritance supported: %s",
          targetType.getSimpleName());
      final Map<String, MethodMetadata> result = new LinkedHashMap<String, MethodMetadata>();
      //遍历api客户端模板接口中的方法
      for (final Method method : targetType.getMethods()) {
        if (method.getDeclaringClass() == Object.class ||
            (method.getModifiers() & Modifier.STATIC) != 0 ||
            Util.isDefault(method)) {
          //无视object的方法，静态的方法，默认方法
          continue;
        }
        //解析单个模板接口中的方法
        final MethodMetadata metadata = parseAndValidateMetadata(targetType, method);

        //出现重复key的方法
        if (result.containsKey(metadata.configKey())) {
          MethodMetadata existingMetadata = result.get(metadata.configKey());
          Type existingReturnType = existingMetadata.returnType();
          Type overridingReturnType = metadata.returnType();
          Type resolvedType = Types.resolveReturnType(existingReturnType, overridingReturnType);
          if (resolvedType.equals(overridingReturnType)) {
            result.put(metadata.configKey(), metadata);
          }
          continue;
        }

        //加入集合
        result.put(metadata.configKey(), metadata);
      }
      return new ArrayList<>(result.values());
    }

    /**
     * @deprecated use {@link #parseAndValidateMetadata(Class, Method)} instead.
     */
    @Deprecated
    public MethodMetadata parseAndValidateMetadata(Method method) {
      return parseAndValidateMetadata(method.getDeclaringClass(), method);
    }

    /**
     * Called indirectly by {@link #parseAndValidateMetadata(Class)}.
     */
    protected MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
      final MethodMetadata data = new MethodMetadata();
      data.targetType(targetType);
      data.method(method);
      data.returnType(
          Types.resolve(targetType, targetType, method.getGenericReturnType()));
      data.configKey(Feign.configKey(targetType, method));
      if (AlwaysEncodeBodyContract.class.isAssignableFrom(this.getClass())) {
        //AlwaysEncodeBodyContract及其子类
        data.alwaysEncodeBody(true);
      }

      if (targetType.getInterfaces().length == 1) {
        //父接口上的类注解
        processAnnotationOnClass(data, targetType.getInterfaces()[0]);
      }
      //类上的注解
      processAnnotationOnClass(data, targetType);


      for (final Annotation methodAnnotation : method.getAnnotations()) {
        //依次处理方法上的注解
        processAnnotationOnMethod(data, methodAnnotation, method);
      }
      if (data.isIgnored()) {
        return data;
      }
      checkState(data.template().method() != null,
          "Method %s not annotated with HTTP method type (ex. GET, POST)%s",
          data.configKey(), data.warnings());
      final Class<?>[] parameterTypes = method.getParameterTypes();
      //比起上面可多获取到参数化类型中的泛型信息
      final Type[] genericParameterTypes = method.getGenericParameterTypes();

      final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
      final int count = parameterAnnotations.length;
      //处理方法的参数
      for (int i = 0; i < count; i++) {
        boolean isHttpAnnotation = false;
        if (parameterAnnotations[i] != null) {
          //处理参数上的注解，返回固定为false
          isHttpAnnotation = processAnnotationsOnParameter(data, parameterAnnotations[i], i);
        }

        if (isHttpAnnotation) {
          data.ignoreParamater(i);
        }

        if ("kotlin.coroutines.Continuation".equals(parameterTypes[i].getName())) {
          data.ignoreParamater(i);
        }

        if (parameterTypes[i] == URI.class) {
          //如果有uri类型的参数，则标记其为url
          data.urlIndex(i);
        } else if (!isHttpAnnotation
            && !Request.Options.class.isAssignableFrom(parameterTypes[i])) {
          //非http注解相关参数（即非@Param等注解，post请求模板参数无需注解，且非Request.Options类，该类非请求体数据模型）
          if (data.isAlreadyProcessed(i)) {
            checkState(data.formParams().isEmpty() || data.bodyIndex() == null,
                "Body parameters cannot be used with form parameters.%s", data.warnings());
          } else if (!data.alwaysEncodeBody()) {
            checkState(data.formParams().isEmpty(),
                "Body parameters cannot be used with form parameters.%s", data.warnings());
            //body参数只能有一个
            checkState(data.bodyIndex() == null,
                "Method has too many Body parameters: %s%s", method, data.warnings());
            //标记该参数为body
            data.bodyIndex(i);
            data.bodyType(
                Types.resolve(targetType, targetType, genericParameterTypes[i]));
          }
        }
      }

      //校验HeaderMap参数的类型
      if (data.headerMapIndex() != null) {
        // check header map parameter for map type
        if (Map.class.isAssignableFrom(parameterTypes[data.headerMapIndex()])) {
          checkMapKeys("HeaderMap", genericParameterTypes[data.headerMapIndex()]);
        }
      }

      //校验QueryMap参数的类型
      if (data.queryMapIndex() != null) {
        if (Map.class.isAssignableFrom(parameterTypes[data.queryMapIndex()])) {
          checkMapKeys("QueryMap", genericParameterTypes[data.queryMapIndex()]);
        }
      }

      return data;
    }

    private static void checkMapString(String name, Class<?> type, Type genericType) {
      checkState(Map.class.isAssignableFrom(type),
          "%s parameter must be a Map: %s", name, type);
      checkMapKeys(name, genericType);
    }

    private static void checkMapKeys(String name, Type genericType) {
      Class<?> keyClass = null;

      // assume our type parameterized
      if (ParameterizedType.class.isAssignableFrom(genericType.getClass())) {
        final Type[] parameterTypes = ((ParameterizedType) genericType).getActualTypeArguments();
        keyClass = (Class<?>) parameterTypes[0];
      } else if (genericType instanceof Class<?>) {
        // raw class, type parameters cannot be inferred directly, but we can scan any extended
        // interfaces looking for any explict types
        final Type[] interfaces = ((Class<?>) genericType).getGenericInterfaces();
        for (final Type extended : interfaces) {
          if (ParameterizedType.class.isAssignableFrom(extended.getClass())) {
            // use the first extended interface we find.
            final Type[] parameterTypes = ((ParameterizedType) extended).getActualTypeArguments();
            keyClass = (Class<?>) parameterTypes[0];
            break;
          }
        }
      }

      if (keyClass != null) {
        checkState(String.class.equals(keyClass),
            "%s key must be a String: %s", name, keyClass.getSimpleName());
      }
    }

    /**
     * Called by parseAndValidateMetadata twice, first on the declaring class, then on the target
     * type (unless they are the same).
     *
     * @param data metadata collected so far relating to the current java method.
     * @param clz the class to process
     */
    protected abstract void processAnnotationOnClass(MethodMetadata data, Class<?> clz);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotation annotations present on the current method annotation.
     * @param method method currently being processed.
     */
    protected abstract void processAnnotationOnMethod(MethodMetadata data,
                                                      Annotation annotation,
                                                      Method method);

    /**
     * @param data metadata collected so far relating to the current java method.
     * @param annotations annotations present on the current parameter annotation.
     * @param paramIndex if you find a name in {@code annotations}, call
     *        {@link #nameParam(MethodMetadata, String, int)} with this as the last parameter.
     * @return true if you called {@link #nameParam(MethodMetadata, String, int)} after finding an
     *         http-relevant annotation.
     */
    protected abstract boolean processAnnotationsOnParameter(MethodMetadata data,
                                                             Annotation[] annotations,
                                                             int paramIndex);

    /**
     * links a parameter name to its index in the method signature.
     */
    protected void nameParam(MethodMetadata data, String name, int i) {
      final Collection<String> names =
          data.indexToName().containsKey(i) ? data.indexToName().get(i) : new ArrayList<String>();
      names.add(name);
      data.indexToName().put(i, names);
    }
  }

  class Default extends DeclarativeContract {
    //@RequestLine("GET /api/{key}")
    static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("^([A-Z]+)[ ]*(.*)$");

    public Default() {
      /*注册加在类上的header注解*/
      //注意：第二个参数是处理器匿名类，里面的逻辑为处理器逻辑，其调用在别的地方，不是下面这里
      super.registerClassAnnotation(Headers.class, (header, data) -> {//参数列表：E annotation, MethodMetadata metadata
        //提取注解里的数组参数
        final String[] headersOnType = header.value();
        //校验至少有一个参数
        checkState(headersOnType.length > 0, "Headers annotation was empty on type %s.",
            data.configKey());
        //将header字符串数组转成map（headerKey:[headerValue1,headerValue2]）
        final Map<String, Collection<String>> headers = toMap(headersOnType);

        //聚合MethodMetadata.template()里原本的header数据，再替换
        headers.putAll(data.template().headers());
        data.template().headers(null); // to clear
        data.template().headers(headers);
      });

      /*注册加在方法上的RequestLine注解*/
      super.registerMethodAnnotation(RequestLine.class, (ann, data) -> {
        final String requestLine = ann.value();
        checkState(emptyToNull(requestLine) != null,
            "RequestLine annotation was empty on method %s.", data.configKey());

        //校验uri参数规则
        final Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestLineMatcher.find()) {
          throw new IllegalStateException(String.format(
              "RequestLine annotation didn't start with an HTTP verb on method %s",
              data.configKey()));
        } else {
          //提取出uri参数中的http方法和uri
          data.template().method(HttpMethod.valueOf(requestLineMatcher.group(1)));
          data.template().uri(requestLineMatcher.group(2));
        }
        data.template().decodeSlash(ann.decodeSlash());
        data.template()
            .collectionFormat(ann.collectionFormat());
      });

      /*注册加在方法上的Body注解*/
      super.registerMethodAnnotation(Body.class, (ann, data) -> {
        final String body = ann.value();
        checkState(emptyToNull(body) != null, "Body annotation was empty on method %s.",
            data.configKey());

        // json curly braces must be escaped!
        //  @Body("%7B\"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
        if (body.indexOf('{') == -1) {
          //如果body体里无“{”，则代表无参数，直接作为请求体
          data.template().body(body);
        } else {
          //如果body体里有“{”，则代表有参数，作为请求体模板
          data.template().bodyTemplate(body);
        }
      });

      /*注册加在方法上的Headers注解*/
      super.registerMethodAnnotation(Headers.class, (header, data) -> {
        final String[] headersOnMethod = header.value();
        checkState(headersOnMethod.length > 0, "Headers annotation was empty on method %s.",
            data.configKey());
        data.template().headers(toMap(headersOnMethod));
      });

      /*注册加在参数上的Param注解*/
      super.registerParameterAnnotation(Param.class, (paramAnnotation, data, paramIndex) -> {
        final String annotationName = paramAnnotation.value();
        final Parameter parameter = data.method().getParameters()[paramIndex];
        final String name;
        //@Param注解参数所要替换的模板变量名
        if (emptyToNull(annotationName) == null && parameter.isNamePresent()) {
          //如果注解没有指定名字，使用变量名
          name = parameter.getName();
        } else {
          name = annotationName;
        }
        checkState(emptyToNull(name) != null, "Param annotation was empty on param %s.",
            paramIndex);

        //
        nameParam(data, name, paramIndex);

        //
        final Class<? extends Param.Expander> expander = paramAnnotation.expander();
        if (expander != Param.ToStringExpander.class) {
          data.indexToExpanderClass().put(paramIndex, expander);
        }
        //
        if (!data.template().hasRequestVariable(name)) {
          data.formParams().add(name);
        }
      });
      super.registerParameterAnnotation(QueryMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.queryMapIndex() == null,
            "QueryMap annotation was present on multiple parameters.");
        data.queryMapIndex(paramIndex);
      });
      super.registerParameterAnnotation(HeaderMap.class, (queryMap, data, paramIndex) -> {
        checkState(data.headerMapIndex() == null,
            "HeaderMap annotation was present on multiple parameters.");
        data.headerMapIndex(paramIndex);
      });
    }

    private static Map<String, Collection<String>> toMap(String[] input) {
      //将header数组转换成支持重复header值的map结构（headerKey:[headerValue1,headerValue2]）
      final Map<String, Collection<String>> result =
          new LinkedHashMap<String, Collection<String>>(input.length);
      for (final String header : input) {
        //通过冒号识别header的键和值
        final int colon = header.indexOf(':');
        final String name = header.substring(0, colon);
        if (!result.containsKey(name)) {
          //没有该header时初始化
          result.put(name, new ArrayList<String>(1));
        }
        //将该header值追加到值列表后面
        result.get(name).add(header.substring(colon + 1).trim());
      }
      return result;
    }
  }
}

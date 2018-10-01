package com.josh.mvcframework.servlet;

import com.josh.mvcframework.annotation.GPAutowired;
import com.josh.mvcframework.annotation.GPController;
import com.josh.mvcframework.annotation.GPRequestMapping;
import com.josh.mvcframework.annotation.GPService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

/**
 * Created by sulin on 2018/9/30.
 */
public class GPDispatcherServlet extends HttpServlet {

    private Properties contextConfig = new Properties();
    private List<String> classNames = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>();
    private Map<String, Method> handlerMapping = new HashMap<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 根据URL调用不同的方法
        try {
            doDispatch(req, resp);
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {
        if (this.handlerMapping.isEmpty()) {
            return;
        }
        // 它是一个绝对路径
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        uri = uri.replace(contextPath, "").replaceAll("/+", "/");
        if (!this.handlerMapping.containsKey(uri)) {
            resp.getWriter().write("404 Not found!!");
            return;
        }
        Method method = this.handlerMapping.get(uri);
        Map<String, String[]> params = req.getParameterMap();

        // 获取的是形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();

        // 保存实参列表
        Object[] paramValues = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class) {
                paramValues[i] = req;
                continue;
            } else if (parameterType == HttpServletResponse.class) {
                paramValues[i] = resp;
                continue;
            } else if (parameterType == String.class) {
                for (Map.Entry<String, String[]> param : params.entrySet()) {
//                    paramValues[i] = String.valueOf(param.getValue());
                    paramValues[i] = param.getValue()[0];
//                            .replaceAll("\\[|\\]", "")
//                            .replaceAll("\\s", ",");
                }
            }
        }

        String beanName = lowerFirstCase(method.getDeclaringClass().getSimpleName());
        String responsBody = (String) method.invoke(ioc.get(beanName), paramValues);
        resp.getWriter().write(responsBody);
        // 通过反射机制去动态调用取出来的这个方法
//        System.out.println(method.clazz);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件, 并解析
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        //2、扫描所有相关联的类
        doScanner(contextConfig.getProperty("scanPackage"));

        //3、将扫描出来的类进行实例化, 并且存在IOC容器之中
        try {
            doInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }

        //4、完成属性自动赋值, DI依赖注入
        try {
            doAutowired();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        //5、初始化HandlerMapping
        initHandlerMapping();
    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) {
                continue;
            }
            String baseUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }
            Method[] methods = clazz.getMethods();
            for (Method method : clazz.getMethods()) {
                if (method.isAnnotationPresent(GPRequestMapping.class)) {
                    GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                    String url = (baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                    handlerMapping.put(url, method);
                    System.out.println("url = " + url + ", method = " + method);
                }
            }
        }
    }

    private void doAutowired() throws IllegalAccessException {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(GPAutowired.class)) {
                    continue;
                }
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    beanName = field.getType().getName();
                }
                // 暴力访问
                field.setAccessible(true);
                // 用代码, 用反射给字段赋值
                field.set(entry.getValue(), ioc.get(beanName));
            }
        }
    }

    private void doInstance() throws Exception {
        if (classNames.isEmpty()) {
            return;
        }

        try {
            for (String className : classNames) {
                // 要求JVM查找并加载指定的类，也就是说JVM会执行该类的静态代码段。
                Class<?> clazz = Class.forName(className);
                // 不是所有的牛奶都叫特伦苏
                if (clazz.isAnnotationPresent(GPController.class)) {
                    // 默认是类名首字母小写
                    String beanName = lowerFirstCase(clazz.getSimpleName());
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    //1. 默认首字母小写
                    //2. 如果说自定义别名, 那么优先采用别名
                    GPService annotation = clazz.getAnnotation(GPService.class);
                    String beanName = annotation.value();
                    if (beanName.equals("")) {
                        beanName = lowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);
                    //3. 采用接口的全名作为key, 实现的实例作为值
                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> anInterface : interfaces) {
                        if (ioc.containsKey(anInterface.getName())) {
                            throw new Exception("is exists");
                        }
                        ioc.put(anInterface.getName(), instance);
                    }
                } else {
                    // 否则直接忽略
                    continue;
                }
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private String lowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void doScanner(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classDir = new File(url.getFile());
        for (File file : classDir.listFiles()) {
            if (file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                String className = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

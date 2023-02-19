/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.ControllerConfig;
import org.apache.rocketmq.controller.ControllerManager;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.srvutil.ServerUtil;
import org.apache.rocketmq.srvutil.ShutdownHookThread;
import org.slf4j.LoggerFactory;

public class NamesrvStartup {

    private static InternalLogger log;

    /**
     * 解析命令行参数和配置文件参数，装配到该属性中进行存储，它存储全部的配置k-v，包含-c指定的启动文件和-p打印出来的变量
     */
    private static Properties properties = null;

    /**
     * NameServer配置项：从properties中解析出来的全部NameServer配置
     */
    private static NamesrvConfig namesrvConfig = null;

    /**
     * NettyServer的配置项：从properties中解析出来的全部NameServer RPC服务端启动配置
     */
    private static NettyServerConfig nettyServerConfig = null;

    /**
     * NettyClient的配置项：从properties中解析出来的全部NameServer RPC客户端启动配置
     */
    private static NettyClientConfig nettyClientConfig = null;

    /**
     * DledgerController的配置项：从properties中解析出来的全部Controller需要的启动配置
     */
    private static ControllerConfig controllerConfig = null;

    public static void main(String[] args) {
        // 该方法中是启动NameServer的主要逻辑
        main0(args);

        // 这个方法主要是启动内嵌在NameServer中的DLedger Controller，
        // DLedger Controller可以通过配置的形式在NameServer进程中启动，也可以独立部署。
        // 其主要作用是，用来存储和管理 Broker 的 SyncStateSet 列表，
        // 并在某个 Broker 的 Master Broker 下线或⽹络隔离时，主动发出调度指令来切换 Broker 的 Master。
        // 此部分原理暂时不过多介绍，后续将有专题介绍
        controllerManagerMain();
    }

    public static void main0(String[] args) {
        try {
            // 解析命令行参数及配置文件中的配置，并装配到本类的各个属性上
            parseCommandlineAndConfigFile(args);

            // 创建并启动NameServer Controller
            createAndStartNamesrvController();
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }

    }

    public static void controllerManagerMain() {
        try {
            if (namesrvConfig.isEnableControllerInNamesrv()) {
                createAndStartControllerManager();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * 命令行参数解析及配置文件解析，装配属性
     *
     * @param args 命令行参数
     * @throws Exception 异常
     */
    public static void parseCommandlineAndConfigFile(String[] args) throws Exception {

        // 设置当前MQ版本设置为全局环境变量，方便在本项目的任何地方进行获取
        // key为rocketmq.remoting.version，当前版本值为：Version.V5_0_0，数值为413
        System.setProperty(RemotingCommand.REMOTING_VERSION_KEY, Integer.toString(MQVersion.CURRENT_VERSION));
        //PackageConflictDetect.detectFastjson();

        // 构建-h 和 -n 的命令行选项option，并将两个命令行选项加入到options中
        // 发散一下，其实可以在buildCommandlineOptions加一些自定义代码，比如可以设置NameServer的启动端口等
        Options options = ServerUtil.buildCommandlineOptions(new Options());

        // 从命令行参数中解析各个命令行选项，将选项名和选项值加载到CommandLine对象中，
        // 其中本类的buildCommandlineOptions方法，向options中加入了两个选项，分别是configFile和printConfigItem
        // 在解析命令行选项的时候，如果发现命令行选项中包含-h或者--help，那么NameServer是不会启动的，只会打印命令行帮助信息，打印结果如下所示：
        // usage: mqnamesrv [-c <arg>] [-h] [-n <arg>] [-p]
        // -c,--configFile <arg>    Name server config properties file
        // -h,--help                Print help
        // -n,--namesrvAddr <arg>   Name server address list, eg: '192.168.0.1:9876;192.168.0.2:9876'
        // -p,--printConfigItem     Print all config items
        // 如果你自定义了一些必需的命令行选项，但是在启动的时候，又没有填写这些选项，那么是会解析出错，出错后，也会打印出各个选项的信息
        CommandLine commandLine = ServerUtil.parseCmdLine("mqnamesrv", args, buildCommandlineOptions(options), new PosixParser());
        if (null == commandLine) {
            System.exit(-1);
            return;
        }

        // 创建配置对象
        namesrvConfig = new NamesrvConfig();
        nettyServerConfig = new NettyServerConfig();
        nettyClientConfig = new NettyClientConfig();

        // 这里默认启动监听的端口是9876，其实可以在上面的命令行选项中加入一个自定义的选型，并设置一个端口选项
        // 这样就可以在启动的时候通过命令行传入监听端口
        nettyServerConfig.setListenPort(9876);
        controllerConfig = new ControllerConfig();

        // 获取命令行中configFile的值，这个值是配置文件的位置，如果包含这个参数，那么将解析该配置文件，将配置加载到各配置对象中
        if (commandLine.hasOption('c')) {
            String file = commandLine.getOptionValue('c');
            if (file != null) {
                InputStream in = new BufferedInputStream(Files.newInputStream(Paths.get(file)));
                properties = new Properties();
                properties.load(in);
                MixAll.properties2Object(properties, namesrvConfig);
                MixAll.properties2Object(properties, nettyServerConfig);
                MixAll.properties2Object(properties, nettyClientConfig);
                MixAll.properties2Object(properties, controllerConfig);

                namesrvConfig.setConfigStorePath(file);

                System.out.printf("load config properties file OK, %s%n", file);
                in.close();
            }
        }

        // 如果在启动参数加上选项-p，那么将打印出namesrvConfig和nettyServerConfig的属性值信息
        // 其中namesrvConfig主要配置了namesrv的信息，nettyServerConfig主要配置了netty的属性值信息
        // 配置打印结束后就退出进程
        if (commandLine.hasOption('p')) {
            MixAll.printObjectProperties(null, namesrvConfig);
            MixAll.printObjectProperties(null, nettyServerConfig);
            MixAll.printObjectProperties(null, nettyClientConfig);
            MixAll.printObjectProperties(null, controllerConfig);
            System.exit(0);
        }

        // 这里再解析一遍命令行参数，装配namesrvConfig，从代码执行顺序来看，
        // 命令行中的参数优先级要高于配置文件中的配置，因为这里可以覆盖配置文件中的值
        MixAll.properties2Object(ServerUtil.commandLine2Properties(commandLine), namesrvConfig);

        // 如果没有配置系统属性值rocketmq.home.dir或者环境变量ROCKETMQ_HOME，那么将直接退出
        // rocketmq_home默认来源于配置rocketmq.home.dir，如果没有配置，将从环境变量中获取ROCKETMQ_HOME参数
        if (null == namesrvConfig.getRocketmqHome()) {
            System.out.printf("Please set the %s variable in your environment to match the location of the RocketMQ installation%n", MixAll.ROCKETMQ_HOME_ENV);
            System.exit(-2);
        }

        // 自定义日志配置logback_namesrv.xml，可以了解博文(https://www.jianshu.com/p/3b9cb5e22052)来理解日志的配置加载
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(lc);
        lc.reset();
        configurator.doConfigure(namesrvConfig.getRocketmqHome() + "/conf/logback_namesrv.xml");

        log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);

        // 启动过程中打印配置日志
        MixAll.printObjectProperties(log, namesrvConfig);
        MixAll.printObjectProperties(log, nettyServerConfig);

    }

    /**
     * 创建并启动NameServerController
     */
    public static void createAndStartNamesrvController() throws Exception {
        // 创建NameServerController对象
        NamesrvController controller = createNamesrvController();
        // 启动NameServerController对象
        start(controller);
        String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
        log.info(tip);
        System.out.printf("%s%n", tip);
    }

    /**
     * 创建NameServerController
     */
    public static NamesrvController createNamesrvController() {

        // 构建NamesrvController对象，构造方法里面创建了KVConfigManager、BrokerHousekeepingService、RouteInfoManager、Configuration对象
        final NamesrvController controller = new NamesrvController(namesrvConfig, nettyServerConfig, nettyClientConfig);
        // remember all configs to prevent discard
        // 将配置注册到Configuration中，并被controller持有，防止配置丢失
        controller.getConfiguration().registerConfig(properties);
        return controller;
    }

    /**
     * 启动NameServerController
     */
    public static NamesrvController start(final NamesrvController controller) throws Exception {

        if (null == controller) {
            throw new IllegalArgumentException("NamesrvController is null");
        }

        // 第一步：进行controller的初始化工作
        boolean initResult = controller.initialize();

        // 如果初始化controller失败，则直接退出
        if (!initResult) {
            // 关闭controller，释放资源
            controller.shutdown();
            System.exit(-3);
        }

        // 第二步：注册钩子函数，当JVM正常退出的时候，将执行该钩子函数，执行关闭controller释放资源
        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, (Callable<Void>) () -> {
            controller.shutdown();
            return null;
        }));

        // 第三步：启动controller
        controller.start();

        return controller;
    }

    public static void createAndStartControllerManager() throws Exception {
        ControllerManager controllerManager = createControllerManager();
        start(controllerManager);
        String tip = "The ControllerManager boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
        log.info(tip);
        System.out.printf("%s%n", tip);
    }

    public static ControllerManager createControllerManager() throws Exception {
        NettyServerConfig controllerNettyServerConfig = (NettyServerConfig) nettyServerConfig.clone();
        ControllerManager controllerManager = new ControllerManager(controllerConfig, controllerNettyServerConfig, nettyClientConfig);
        // remember all configs to prevent discard
        controllerManager.getConfiguration().registerConfig(properties);
        return controllerManager;
    }

    public static ControllerManager start(final ControllerManager controllerManager) throws Exception {

        if (null == controllerManager) {
            throw new IllegalArgumentException("ControllerManager is null");
        }

        boolean initResult = controllerManager.initialize();
        if (!initResult) {
            controllerManager.shutdown();
            System.exit(-3);
        }

        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, (Callable<Void>) () -> {
            controllerManager.shutdown();
            return null;
        }));

        controllerManager.start();

        return controllerManager;
    }

    public static void shutdown(final NamesrvController controller) {
        controller.shutdown();
    }

    public static void shutdown(final ControllerManager controllerManager) {
        controllerManager.shutdown();
    }

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Name server config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config items");
        opt.setRequired(false);
        options.addOption(opt);
        return options;
    }

    public static Properties getProperties() {
        return properties;
    }
}

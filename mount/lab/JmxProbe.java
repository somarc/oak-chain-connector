import com.sun.tools.attach.VirtualMachine;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Lab JMX probe for a local AEM JVM (same user, no remote-JMX flags): attaches with the JDK
 * Attach API and starts the local management agent.
 *
 * <pre>
 * java --add-modules jdk.attach JmxProbe.java PID list   'org.apache.jackrabbit.oak:*'
 * java --add-modules jdk.attach JmxProbe.java PID attrs  'OBJECT_NAME'
 * java --add-modules jdk.attach JmxProbe.java PID ops    'OBJECT_NAME'
 * java --add-modules jdk.attach JmxProbe.java PID invoke 'OBJECT_NAME' operation [true|false|STRING]
 * java --add-modules jdk.attach JmxProbe.java PID set    'OBJECT_NAME' attribute true|false
 * </pre>
 */
public class JmxProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: JmxProbe PID list|attrs|ops|invoke|set PATTERN|NAME [operation|attribute [value]]");
            System.exit(2);
        }
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(vm.startLocalManagementAgent()))) {
            MBeanServerConnection server = connector.getMBeanServerConnection();
            ObjectName name = new ObjectName(args[2]);
            switch (args[1]) {
                case "list" -> {
                    Set<String> names = new TreeSet<>();
                    server.queryNames(name, null).forEach(n -> names.add(n.toString()));
                    names.forEach(System.out::println);
                }
                case "attrs" -> {
                    for (MBeanAttributeInfo a : server.getMBeanInfo(name).getAttributes()) {
                        try {
                            System.out.println(a.getName() + " = " + server.getAttribute(name, a.getName()));
                        } catch (Exception e) {
                            System.out.println(a.getName() + " ! " + e.getClass().getSimpleName());
                        }
                    }
                }
                case "ops" -> {
                    for (MBeanOperationInfo o : server.getMBeanInfo(name).getOperations()) {
                        System.out.println(o.getName() + Arrays.toString(Arrays.stream(o.getSignature()).map(MBeanParameterInfo::getType).toArray()));
                    }
                }
                case "invoke" -> {
                    Object result;
                    if (args.length <= 4) {
                        result = server.invoke(name, args[3], new Object[0], new String[0]);
                    } else if (args[4].equals("true") || args[4].equals("false")) {
                        result = server.invoke(name, args[3], new Object[] {Boolean.parseBoolean(args[4])}, new String[] {"boolean"});
                    } else {
                        result = server.invoke(name, args[3], new Object[] {args[4]}, new String[] {String.class.getName()});
                    }
                    System.out.println(result);
                }
                case "set" -> {
                    server.setAttribute(name, new javax.management.Attribute(args[3], Boolean.parseBoolean(args[4])));
                    System.out.println(args[3] + " = " + server.getAttribute(name, args[3]));
                }
                default -> throw new IllegalArgumentException(args[1]);
            }
        } finally {
            vm.detach();
        }
    }
}

package com.nosqldriver.util;

import com.nosqldriver.VisibleForPackage;
import org.json.simple.parser.JSONParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.nosqldriver.util.DateParser.*;
import static java.lang.String.format;
import static java.util.Base64.*;

// Only functions allow discovering generic types of arguments; lambdas do not allow this.
@SuppressWarnings({"Convert2Lambda", "Anonymous2MethodRef"})
@VisibleForPackage
public class StandardFunctions {
    private static DataUtil dataUtil = new DataUtil();

    private static Function<Object, Date> date = arg -> {
        if (arg instanceof Date) {
            return (Date)arg;
        }
        if (arg == null) {
            return new Date();
        }
        if (arg instanceof Number) {
            return new Date(((Number)arg).longValue());
        }
        if (arg instanceof String) {
            return parse((String) arg, null);
        }
        return SneakyThrower.sneakyThrow(new SQLException("Wrong argument " + arg + " type: " + (arg.getClass())));
    };

    @VisibleForPackage
    public static final Map<String, Object> functions = new HashMap<>();
    static {
        final Function<Object, Integer> objLength = new @TypeGroup(String.class) Function<Object, Integer>() {
            @Override
            public Integer apply(Object o) {
                if (o == null) {
                    return 0;
                }
                if (o instanceof CharSequence) {
                    return ((CharSequence)o).length();
                }
                if (o instanceof Collection) {
                    return ((Collection)o).size();
                }
                if (o instanceof Map) {
                    return ((Map)o).size();
                }
                throw new IllegalArgumentException("function length() does not support " + o);
            }
        };
        functions.put("len", objLength);
        functions.put("length", objLength);

        functions.put("ascii", new @TypeGroup(String.class) Function<String, Integer>() {
            @Override
            public Integer apply(String s) {
                return s.length() > 0 ? (int) s.charAt(0) : null;
            }
        });
        functions.put("char", new @TypeGroup(String.class) Function<Integer, String>() {
            @Override
            public String apply(Integer code) {
                return code == null ? null : new String(new char[] {(char)code.intValue()});
            }
        });
        functions.put("locate", new VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                String subStr = (String) args[0];
                String str = (String) args[1];
                int offset = args.length > 2 ? (Integer) args[2] - 1 : 0;
                return str.indexOf(subStr) + 1 - offset;
            }
        });
        functions.put("instr", new @TypeGroup(String.class) BiFunction<String, String, Integer>() {
            @Override
            public Integer apply(String str, String subStr) {
                return str.indexOf(subStr) + 1;
            }
        });
        functions.put("trim", new @TypeGroup(String.class) Function<String, String>() {

            @Override
            public String apply(String s) {
                return s == null ? null : s.trim();
            }
        });
        functions.put("ltrim", new @TypeGroup(String.class) Function<String, String>() {
            @Override
            public String apply(String s) {
                return s == null ? null : s.replaceFirst("^ *", "");
            }
        });
        functions.put("rtrim", new @TypeGroup(String.class) Function<String, String>() {
            @Override
            public String apply(String s) {
                return s == null ? null : s.replaceFirst(" *$", "");
            }
        });
        functions.put("strcmp", new @TypeGroup(String.class) BiFunction<String, String, Integer>() {

            @Override
            public Integer apply(String s, String s2) {
                return s.compareTo(s2);
            }
        });
        functions.put("left", new @TypeGroup(String.class) BiFunction<String, Integer, String>() {
            @Override
            public String apply(String s, Integer n) {
                return s.substring(0, n);
            }
        });

        Function<String, String> toLowerCase = new @TypeGroup(String.class) Function<String, String>() {
            @Override
            public String apply(String s) {
                return s == null ? null : s.toLowerCase();
            }
        };
        functions.put("lower", toLowerCase);
        functions.put("lcase", toLowerCase);

        Function<String, String> toUpperCase = new @TypeGroup(String.class) Function<String, String>() {
            @Override
            public String apply(String s) {
                return s == null ? null : s.toUpperCase();
            }
        };
        functions.put("upper", toUpperCase);
        functions.put("ucase", toUpperCase);

        functions.put("str", new @TypeGroup(String.class) Function<Object, String>() {
            @Override
            public String apply(Object o) {
                return String.valueOf(o);
            }
        });
        functions.put("space", new @TypeGroup(String.class) Function<Integer, String>() {
            @Override
            public String apply(Integer i) {
                return i == 0 ? "" : format("%" + i + "c", ' ');
            }
        });
        functions.put("reverse", new @TypeGroup(String.class) Function<String, String>() {
            @Override
            public String apply(String s) {
                return s == null ? null : new StringBuilder(s).reverse().toString();
            }
        });
        functions.put("to_base64", new @TypeGroup(String.class) Function<Object, String>() {
            @Override
            public String apply(Object b) {
                return b == null ? null : getEncoder().encodeToString(b instanceof String ? ((String)b).getBytes() : (byte[])b);
            }
        });
        functions.put("from_base64", new @TypeGroup(String.class) Function<String, byte[]>() {
            @Override
            public byte[] apply(String s) {
                return s == null ? null : java.util.Base64.getDecoder().decode(s);
            }
        });
        functions.put("substring", new @TypeGroup(String.class) TriFunction<String, Integer, Integer, String>() {

            @Override
            public String apply(String s, Integer start, Integer length) {
                return s.substring(start - 1, length);
            }
        });
        functions.put("concat", new @TypeGroup(String.class) VarargsFunction<Object, String>() {
            @Override
            public String apply(Object... args) {
                return Arrays.stream(args).map(String::valueOf).collect(Collectors.joining());
            }
        });
        functions.put("concat_ws", new @TypeGroup(String.class) VarargsFunction<Object, String>() {
            @Override
            public String apply(Object... args) {
                return Arrays.stream(args).skip(1).map(String::valueOf).collect(Collectors.joining((String)args[0]));
            }
        });
        functions.put("coalesce", new @TypeGroup(Object.class) VarargsFunction<Object, Object>() {
            @Override
            public Object apply(Object... args) {
                return Arrays.stream(args).filter(e -> e != null).findFirst().orElse(null);
            }
        });
        functions.put("date", new @TypeGroup(Date.class) VarargsFunction<Object, Date>() {
            @Override
            public Date apply(Object... args) {
                return date.apply(args.length > 0 ? args[0] : null);
            }
        });
        functions.put("calendar", new @TypeGroup(Date.class) VarargsFunction<Object, Calendar>() {
            @Override
            public Calendar apply(Object... args) {
                return calendar(args);
            }
        });
        functions.put("now", new @TypeGroup({Date.class, Number.class}) Supplier<Long>() {
            @Override
            public Long get() {
                return System.currentTimeMillis();
            }
        });
        functions.put("year", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.YEAR);
            }
        });
        functions.put("month", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.MONTH) + 1;
            }
        });
        functions.put("dayofmonth", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.DAY_OF_MONTH);
            }
        });
        functions.put("hour", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.HOUR_OF_DAY);
            }
        });
        functions.put("minute", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.MINUTE);
            }
        });
        functions.put("second", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.SECOND);
            }
        });
        functions.put("millisecond", new @TypeGroup(Date.class) VarargsFunction<Object, Integer>() {
            @Override
            public Integer apply(Object... args) {
                return calendar(args).get(Calendar.MILLISECOND);
            }
        });
        functions.put("epoch", new @TypeGroup({Date.class, Long.class}) VarargsFunction<Object, Long>() {
            @Override
            public Long apply(Object... args) {
                return parse((String)args[0], args.length > 1 ? (String)args[1] : null).getTime();
            }
        });
        functions.put("millis", new @TypeGroup(Date.class) Function<Date, Long>() {
            @Override
            public Long apply(Date date) {
                return date.getTime();
            }
        });
        functions.put("map", new @TypeGroup({String.class, Map.class}) Function<String, Map<?, ?>>() {
            @Override
            public Map<?, ?> apply(String json) {
                return (Map<?, ?>)parseJson(json);
            }
        });
        functions.put("list", new @TypeGroup({String.class, List.class}) Function<String, List<?>>() {
            @Override
            public List<?> apply(String json) {
                return dataUtil.toList(parseJson(json));
            }
        });
        functions.put("array", new @TypeGroup({String.class, Array.class})  Function<String, Object[]>() {
            @Override
            public Object[] apply(String json) {
                return dataUtil.toArray(parseJson(json));
            }
        });


        // Numeric functions
        functions.put("abs", new @TypeGroup(Number.class)  Function<Number, Number>() {
            @Override
            public Number apply(Number value) {
                if (value == null) {
                    return null;
                }
                if (value instanceof Double || value instanceof BigDecimal) {
                    return Math.abs(value.doubleValue());
                }
                if (value instanceof Float) {
                    return Math.abs((Float)value);
                }
                if (value instanceof Long || value instanceof AtomicLong) {
                    return Math.abs(value.longValue());
                }
                if (value instanceof Integer || value instanceof Short || value instanceof Byte || value instanceof AtomicInteger) {
                    return Math.abs(value.intValue());
                }
                throw new IllegalArgumentException("abs() supports only numeric arguments");
            }
        });

        functions.put("acos", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.acos(value.doubleValue());
            }
        });
        functions.put("asin", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.asin(value.doubleValue());
            }
        });
        functions.put("atan", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.atan(value.doubleValue());
            }
        });
        functions.put("atan2", new @TypeGroup(Number.class)  BiFunction<Number, Number, Double>() {
            @Override
            public Double apply(Number y, Number x) {
                return Math.atan2(y.doubleValue(), x.doubleValue());
            }
        });
        functions.put("cos", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.cos(value.doubleValue());
            }
        });
        functions.put("cot", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return 1.0 / Math.tan(value.doubleValue());
            }
        });
        functions.put("exp", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.exp(value.doubleValue());
            }
        });
        functions.put("ln", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.log(value.doubleValue());
            }
        });
        functions.put("log10", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.log10(value.doubleValue());
            }
        });
        functions.put("log2", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number value) {
                return Math.log(value.doubleValue()) / Math.log(2);
            }
        });
        functions.put("pi", new @TypeGroup(Number.class) Supplier<Double>() {
            @Override
            public Double get() {
                return Math.PI;
            }
        });
        BiFunction<Number, Number, Double> pow = new @TypeGroup(Number.class) BiFunction<Number, Number, Double>() {
            @Override
            public Double apply(Number a, Number b) {
                return Math.pow(a.doubleValue(), b.doubleValue());
            }
        };
        functions.put("pow", pow);
        functions.put("power", pow);

        functions.put("degrees", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number angrad) {
                return Math.toDegrees(angrad.doubleValue());
            }
        });
        functions.put("radians", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number angdeg) {
                return Math.toRadians(angdeg.doubleValue());
            }
        });
        functions.put("sin", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number a) {
                return Math.sin(a.doubleValue());
            }
        });
        functions.put("tan", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number a) {
                return Math.tan(a.doubleValue());
            }
        });
        functions.put("ceil", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number a) {
                return Math.ceil(a.doubleValue());
            }
        });
        functions.put("floor", new @TypeGroup(Number.class)  Function<Number, Double>() {
            @Override
            public Double apply(Number a) {
                return Math.floor(a.doubleValue());
            }
        });
        functions.put("round", new @TypeGroup(Number.class)  BiFunction<Number, Integer, Double>() {
            @Override
            public Double apply(Number value, Integer places) {
                return BigDecimal.valueOf(value.doubleValue()).setScale(places, RoundingMode.HALF_UP).doubleValue();
            }
        });
        functions.put("rand", new VarargsFunction<Object, Double>() {
            @Override
            public Double apply(Object... t) {
                return (t.length > 0 ? new Random(((Number)t[0]).longValue()) : new Random()).nextDouble();
            }
        });
    }


    private static Date parse(String str, String fmt) {
        try {
            Date d = fmt != null ? new SimpleDateFormat(fmt).parse(str) : date(str);
            return new Date(d.getTime());
        } catch (ParseException e) {
            return SneakyThrower.sneakyThrow(new SQLException(e));
        }
    }

    private static Calendar calendar(Object[] args) {
        Date d = date.apply(args.length > 0 ? args[0] : null);
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return c;
    }

    private static <T> T parseJson(String json) {
        try {
            //noinspection unchecked
            return (T)new JSONParser().parse(json);
        } catch (org.json.simple.parser.ParseException e) {
            return SneakyThrower.sneakyThrow(new SQLException(e));
        }
    }
}

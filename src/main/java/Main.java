import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Main {

    record Student(int id, String name, String groupName, double grade) {
    }

    // ЗАДАНИЕ 1: работа со студентами через DataSource.
    static class StudentDao {
        private final DataSource dataSource;

        StudentDao(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void save(Student student) throws SQLException {
            String sql = """
                    INSERT INTO students(id, name, group_name, grade)
                    VALUES (?, ?, ?, ?)
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setInt(1, student.id());
                statement.setString(2, student.name());
                statement.setString(3, student.groupName());
                statement.setDouble(4, student.grade());

                statement.executeUpdate();
            }
        }

        Student findById(int id) throws SQLException {
            String sql = "SELECT * FROM students WHERE id = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setInt(1, id);

                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return readStudent(result);
                    }

                    return null;
                }
            }
        }

        List<Student> findAll() throws SQLException {
            List<Student> students = new ArrayList<>();

            String sql = "SELECT * FROM students ORDER BY id";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    students.add(readStudent(result));
                }
            }

            return students;
        }

        boolean update(Student student) throws SQLException {
            String sql = """
                    UPDATE students
                    SET name = ?, group_name = ?, grade = ?
                    WHERE id = ?
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setString(1, student.name());
                statement.setString(2, student.groupName());
                statement.setDouble(3, student.grade());
                statement.setInt(4, student.id());

                return statement.executeUpdate() == 1;
            }
        }

        boolean delete(int id) throws SQLException {
            String sql = "DELETE FROM students WHERE id = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setInt(1, id);

                return statement.executeUpdate() == 1;
            }
        }

        private Student readStudent(ResultSet result)
                throws SQLException {

            return new Student(
                    result.getInt("id"),
                    result.getString("name"),
                    result.getString("group_name"),
                    result.getDouble("grade")
            );
        }
    }

    // Создание таблиц и функции H2.
    static void createDatabase(DataSource dataSource)
            throws SQLException {

        String[] commands = {
                """
                CREATE TABLE students (
                    id INT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    group_name VARCHAR(30) NOT NULL,
                    grade DOUBLE NOT NULL
                )
                """,
                """
                CREATE TABLE courses (
                    id INT PRIMARY KEY,
                    title VARCHAR(100) NOT NULL,
                    free_seats INT NOT NULL CHECK (free_seats >= 0)
                )
                """,
                """
                CREATE TABLE enrollments (
                    student_id INT REFERENCES students(id),
                    course_id INT REFERENCES courses(id),
                    PRIMARY KEY (student_id, course_id)
                )
                """,
                """
                CREATE TABLE benchmark_students (
                    id INT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL
                )
                """,
                """
                INSERT INTO courses(id, title, free_seats)
                VALUES (1, 'Java', 1)
                """,
                """
                CREATE ALIAS GROUP_AVERAGE
                FOR "Main.groupAverage"
                """
        };

        try (Connection connection = dataSource.getConnection()) {
            for (String sql : commands) {
                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {

                    statement.execute();
                }
            }
        }
    }

    // ЗАДАНИЕ 2: одинаковые данные, одна транзакция в каждом тесте.
    static double insertStudents(
            DataSource dataSource,
            boolean batch
    ) throws SQLException {

        String sql = """
                INSERT INTO benchmark_students(id, name)
                VALUES (?, ?)
                """;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                // Очистка не входит в измеряемое время.
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM benchmark_students")) {

                    clear.executeUpdate();
                }

                connection.commit();

                long start = System.nanoTime();

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {

                    for (int i = 1; i <= 1000; i++) {
                        statement.setInt(1, i);
                        statement.setString(2, "Студент " + i);

                        if (batch) {
                            statement.addBatch();
                        } else {
                            statement.executeUpdate();
                        }
                    }

                    if (batch) {
                        statement.executeBatch();
                    }
                }

                connection.commit();

                return (System.nanoTime() - start) / 1_000_000.0;

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    // ЗАДАНИЕ 3: запись на курс и уменьшение числа мест.
    static void enroll(
            DataSource dataSource,
            int studentId,
            int courseId
    ) throws SQLException {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                // Уменьшение возможно только при наличии места.
                String updateSql = """
                        UPDATE courses
                        SET free_seats = free_seats - 1
                        WHERE id = ? AND free_seats > 0
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(updateSql)) {

                    statement.setInt(1, courseId);

                    if (statement.executeUpdate() == 0) {
                        throw new SQLException(
                                "Курс не найден или свободных мест нет"
                        );
                    }
                }

                String insertSql = """
                        INSERT INTO enrollments(student_id, course_id)
                        VALUES (?, ?)
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(insertSql)) {

                    statement.setInt(1, studentId);
                    statement.setInt(2, courseId);

                    statement.executeUpdate();
                }

                connection.commit();

                System.out.println(
                        "COMMIT: студент " + studentId + " записан"
                );

            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    static void printCourseState(DataSource dataSource)
            throws SQLException {

        String sql = """
                SELECT free_seats,
                       (SELECT COUNT(*)
                        FROM enrollments
                        WHERE course_id = 1) AS enrolled
                FROM courses
                WHERE id = 1
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            if (result.next()) {
                System.out.println(
                        "Свободных мест: " + result.getInt("free_seats")
                                + ", записано: " + result.getInt("enrolled")
                );
            }
        }
    }

    // ЗАДАНИЕ 4: Java-метод, зарегистрированный как функция H2.
    // Connection передаёт сама база данных.
    public static Double groupAverage(
            Connection connection,
            String groupName
    ) throws SQLException {

        String sql = """
                SELECT AVG(grade)
                FROM students
                WHERE group_name = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, groupName);

            try (ResultSet result = statement.executeQuery()) {
                result.next();

                double average = result.getDouble(1);

                return result.wasNull() ? null : average;
            }
        }
    }

    static void callAverage(DataSource dataSource)
            throws SQLException {

        try (Connection connection = dataSource.getConnection();
             CallableStatement statement = connection.prepareCall(
                     "{? = call GROUP_AVERAGE(?)}")) {

            statement.registerOutParameter(1, Types.DOUBLE);
            statement.setString(2, "ПО-31");

            statement.execute();

            double average = statement.getDouble(1);

            if (statement.wasNull()) {
                System.out.println("В группе нет студентов");
            } else {
                System.out.println("Средний балл группы: " + average);
            }
        }
    }

    public static void main(String[] args) {
        JdbcDataSource dataSource = new JdbcDataSource();

        dataSource.setURL(
                "jdbc:h2:mem:homework;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try {
            createDatabase(dataSource);

            System.out.println("=== ЗАДАНИЕ 1: CRUD ===");

            StudentDao dao = new StudentDao(dataSource);

            dao.save(new Student(1, "Роман", "ПО-31", 5));
            dao.save(new Student(2, "Анна", "ПО-31", 4));
            dao.save(new Student(3, "Иван", "ПО-32", 3));

            System.out.println("Поиск: " + dao.findById(1));
            System.out.println("Все студенты: " + dao.findAll());

            System.out.println(
                    "Обновлён: "
                            + dao.update(new Student(
                            3, "Иван", "ПО-32", 4
                    ))
            );

            System.out.println("После обновления: " + dao.findById(3));
            System.out.println("Удалён: " + dao.delete(3));
            System.out.println("После удаления: " + dao.findById(3));

            System.out.println("\n=== ЗАДАНИЕ 2: ЦИКЛ И BATCH ===");

            // Прогрев: эти результаты не выводим.
            insertStudents(dataSource, false);
            insertStudents(dataSource, true);

            for (int attempt = 1; attempt <= 3; attempt++) {
                // Чередуем порядок тестов.
                double loopTime;
                double batchTime;

                if (attempt % 2 == 1) {
                    loopTime = insertStudents(dataSource, false);
                    batchTime = insertStudents(dataSource, true);
                } else {
                    batchTime = insertStudents(dataSource, true);
                    loopTime = insertStudents(dataSource, false);
                }

                System.out.printf(
                        "Попытка %d: цикл %.3f мс, batch %.3f мс%n",
                        attempt, loopTime, batchTime
                );
            }

            System.out.println("\n=== ЗАДАНИЕ 3: ТРАНЗАКЦИЯ ===");

            printCourseState(dataSource);

            // Место есть: успешная запись.
            enroll(dataSource, 1, 1);
            printCourseState(dataSource);

            // Мест уже нет: ожидаемый откат.
            try {
                enroll(dataSource, 2, 1);
            } catch (SQLException e) {
                System.out.println("ROLLBACK: " + e.getMessage());
            }

            printCourseState(dataSource);

            System.out.println("\n=== ЗАДАНИЕ 4: ФУНКЦИЯ ===");

            callAverage(dataSource);

        } catch (SQLException e) {
            System.out.println("Ошибка SQL: " + e.getMessage());
            System.out.println("SQLState: " + e.getSQLState());
            e.printStackTrace();
        }
    }
}
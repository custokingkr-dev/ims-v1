# Custoking IMS Backend

This backend is a lightweight Java 21 Spring Boot API designed for easy local startup.

## Run

```bash
mvn clean spring-boot:run
```

## Default login

- `superadmin@custoking.com` / `Admin@123`
- `admin@custoking.com` / `Admin@123`

## Notes

- No Flyway
- No JPA
- No Spring Security
- Data is stored in PostgreSQL via Spring Data JPA for local and Docker-based use

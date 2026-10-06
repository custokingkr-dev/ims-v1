package com.custoking.ims.schoolcoreservice.api.internal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
/** Cloud Run IAM and the exact signed MachineCallerFilter authorize identity's explicit machine scope exposes only display labels, not student/contact records. */
@RestController
@RequestMapping("/api/v1/internal/identity-directory")
public class IdentityDirectoryController {
 private final JdbcClient jdbc;
 public IdentityDirectoryController(JdbcClient jdbc) { this.jdbc=jdbc; }
 @GetMapping("/schools/{id}") @Transactional public Map<String,Object> school(@PathVariable long id) { return lookup("schools",id); }
 @GetMapping("/zones/{id}") @Transactional public Map<String,Object> zone(@PathVariable long id) { return lookup("zones",id); }
 private Map<String,Object> lookup(String table,long id) {
  jdbc.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();
  return jdbc.sql("SELECT id,name FROM tenant_school."+table+" WHERE id=:id").param("id",id)
   .query((rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"name",rs.getString("name"))).optional()
   .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Directory entry not found"));
 }
}

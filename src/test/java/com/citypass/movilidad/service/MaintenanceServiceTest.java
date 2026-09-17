package com.citypass.movilidad.service;
import com.citypass.movilidad.dto.*;
import com.citypass.movilidad.exception.*;
import com.citypass.movilidad.model.*;
import com.citypass.movilidad.model.enums.*;
import com.citypass.movilidad.repository.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MaintenanceServiceTest {
    MaintenanceRecordRepository records=mock(MaintenanceRecordRepository.class);
    BikeIncidentRepository incidents=mock(BikeIncidentRepository.class);
    UserRepository users=mock(UserRepository.class);
    BikeService bikes=mock(BikeService.class);
    MaintenanceService service=new MaintenanceService(records,incidents,users,bikes);
    User admin; Bike bike;
    @BeforeEach void setup(){
        Role role=new Role(); role.setName("ADMIN"); admin=new User(); admin.setId(1L); admin.setRole(role); admin.setStatus(UserStatus.ACTIVE);
        bike=new Bike(); bike.setId(2L); bike.setCode("B-2"); bike.setStatus(BikeStatus.AVAILABLE);
        when(users.findById(1L)).thenReturn(Optional.of(admin)); when(bikes.lockActiveBike(2L)).thenReturn(bike);
        when(records.save(any())).thenAnswer(i->{MaintenanceRecord r=i.getArgument(0); r.setId(3L); return r;});
    }
    @Test void createsMaintenanceAndChangesBike(){
        var result=service.create(1L,new MaintenanceCreateRequest(2L,null,"Ajuste general"));
        assertThat(result.status()).isEqualTo(MaintenanceStatus.IN_PROGRESS);
        assertThat(result.startedAt()).isNotNull();
        verify(bikes).sendToMaintenance(bike,admin,"Ajuste general");
    }
    @Test void completesMaintenanceAndRecordsResolution(){
        MaintenanceRecord r=new MaintenanceRecord(); r.setId(3L); r.setBike(bike); r.setCreatedByUser(admin);
        r.setDescription("Ajuste"); r.setStatus(MaintenanceStatus.IN_PROGRESS); r.setStartedAt(java.time.Instant.now());
        when(records.findByIdForUpdate(3L)).thenReturn(Optional.of(r));
        var result=service.complete(1L,3L,new MaintenanceCompleteRequest("Reparada"));
        assertThat(result.status()).isEqualTo(MaintenanceStatus.COMPLETED);
        assertThat(result.completedAt()).isNotNull(); verify(bikes).returnFromMaintenance(bike,admin,"Reparada");
    }
    @Test void rejectsInUseThroughBikeServiceAndInvalidIncident(){
        doThrow(new BusinessRuleException("IN_USE")).when(bikes).sendToMaintenance(any(),any(),any());
        assertThatThrownBy(()->service.create(1L,new MaintenanceCreateRequest(2L,null,"Ajuste")))
                .isInstanceOf(BusinessRuleException.class);
    }
    @Test void rejectsUser(){
        Role role=new Role(); role.setName("USER"); admin.setRole(role);
        assertThatThrownBy(()->service.findAll(1L)).isInstanceOf(ForbiddenOperationException.class);
    }
}

package com.electronics.store.inventory_service.persistence.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@ToString
@Getter
@Setter
@Table(name = "products")
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Product {
    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku", unique = true, nullable = false)
    private String sku;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "characteristics", nullable = false)
    private String characteristics;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "images", columnDefinition = "bytea[]")
    private byte[][] images; //too heavy, consider refactoring

    @Column(nullable = false)
    private String description;
}

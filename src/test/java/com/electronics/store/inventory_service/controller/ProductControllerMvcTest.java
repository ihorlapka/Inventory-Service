package com.electronics.store.inventory_service.controller;

import com.electronics.store.inventory_service.persistence.model.Product;
import com.electronics.store.inventory_service.persistence.services.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void createProduct_shouldReturnCreated() throws Exception {
        UUID productId = UUID.randomUUID();
        CreateProductRequest request = new CreateProductRequest(
                "SKU-001", "Test Product", new BigDecimal("99.99"),
                "{\"color\":\"black\"}", "Test description"
        );

        Product savedProduct = new Product();
        savedProduct.setId(productId);
        savedProduct.setSku(request.sku());
        savedProduct.setName(request.name());
        savedProduct.setPrice(request.price());
        savedProduct.setCharacteristics(request.characteristics());
        savedProduct.setImages(new byte[0][]);
        savedProduct.setDescription(request.description());

        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(savedProduct);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.price").value(99.99))
                .andExpect(jsonPath("$.description").value("Test description"));

        verify(productService).createProduct(any(CreateProductRequest.class));
    }

    @Test
    void createProduct_shouldReturnBadRequest_whenInvalidRequest() throws Exception {
        String invalidJson = """
                {
                    "sku": "",
                    "name": "Test Product",
                    "price": 99.99,
                    "characteristics": "{}",
                    "description": "Test"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details.sku").exists());
    }

    @Test
    void createProduct_shouldReturnBadRequest_whenMissingRequiredFields() throws Exception {
        String invalidJson = """
                {
                    "name": "Test Product"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.sku").exists())
                .andExpect(jsonPath("$.details.price").exists())
                .andExpect(jsonPath("$.details.characteristics").exists());
    }

    @Test
    void getProduct_shouldReturnProduct() throws Exception {
        UUID productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setSku("SKU-001");
        product.setName("Test Product");
        product.setPrice(new BigDecimal("99.99"));
        product.setCharacteristics("{\"color\":\"black\"}");
        product.setImages(new byte[0][]);
        product.setDescription("Test description");

        when(productService.getProduct(productId)).thenReturn(product);

        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.price").value(99.99))
                .andExpect(jsonPath("$.description").value("Test description"));
    }

    @Test
    void getProduct_shouldReturnNotFound_whenProductNotExists() throws Exception {
        UUID productId = UUID.randomUUID();
        when(productService.getProduct(productId))
                .thenThrow(new ProductService.ProductNotFoundException(productId));

        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: " + productId));
    }

    @Test
    void getAllProducts_shouldReturnList() throws Exception {
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();

        Product product1 = new Product();
        product1.setId(productId1);
        product1.setSku("SKU-001");
        product1.setName("Product 1");
        product1.setPrice(new BigDecimal("99.99"));
        product1.setCharacteristics("{}");
        product1.setImages(new byte[0][]);
        product1.setDescription("Description 1");

        Product product2 = new Product();
        product2.setId(productId2);
        product2.setSku("SKU-002");
        product2.setName("Product 2");
        product2.setPrice(new BigDecimal("149.99"));
        product2.setCharacteristics("{}");
        product2.setImages(new byte[0][]);
        product2.setDescription("Description 2");

        when(productService.getAllProducts()).thenReturn(List.of(product1, product2));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(productId1.toString()))
                .andExpect(jsonPath("$[1].id").value(productId2.toString()));
    }

    @Test
    void updateProduct_shouldReturnUpdatedProduct() throws Exception {
        UUID productId = UUID.randomUUID();
        UpdateProductRequest request = new UpdateProductRequest(
                "SKU-001-UPDATED", "Updated Product", new BigDecimal("199.99"),
                "{\"color\":\"white\"}", "Updated description"
        );

        Product updatedProduct = new Product();
        updatedProduct.setId(productId);
        updatedProduct.setSku(request.sku());
        updatedProduct.setName(request.name());
        updatedProduct.setPrice(request.price());
        updatedProduct.setCharacteristics(request.characteristics());
        updatedProduct.setImages(new byte[0][]);
        updatedProduct.setDescription(request.description());

        when(productService.updateProduct(eq(productId), any(UpdateProductRequest.class))).thenReturn(updatedProduct);

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SKU-001-UPDATED"))
                .andExpect(jsonPath("$.name").value("Updated Product"))
                .andExpect(jsonPath("$.price").value(199.99))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    @Test
    void updateProduct_shouldReturnBadRequest_whenInvalidRequest() throws Exception {
        UUID productId = UUID.randomUUID();
        String invalidJson = """
                {
                    "sku": "",
                    "name": "Test",
                    "price": -10,
                    "characteristics": "{}",
                    "description": "Test"
                }
                """;

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.sku").exists())
                .andExpect(jsonPath("$.details.price").exists());
    }

    @Test
    void updateProduct_shouldReturnNotFound_whenProductNotExists() throws Exception {
        UUID productId = UUID.randomUUID();
        UpdateProductRequest request = new UpdateProductRequest(
                "SKU-001", "Test Product", new BigDecimal("99.99"),
                "{}", "Test description"
        );

        when(productService.updateProduct(eq(productId), any(UpdateProductRequest.class)))
                .thenThrow(new ProductService.ProductNotFoundException(productId));

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Product not found with id: " + productId));
    }

    @Test
    void deleteProduct_shouldReturnNoContent() throws Exception {
        UUID productId = UUID.randomUUID();
        doNothing().when(productService).deleteProduct(productId);

        mockMvc.perform(delete("/api/v1/products/{id}", productId))
                .andExpect(status().isNoContent());

        verify(productService).deleteProduct(productId);
    }

    @Test
    void deleteProduct_shouldReturnNotFound_whenProductNotExists() throws Exception {
        UUID productId = UUID.randomUUID();
        doThrow(new ProductService.ProductNotFoundException(productId)).when(productService).deleteProduct(productId);

        mockMvc.perform(delete("/api/v1/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Product not found with id: " + productId));
    }

    @Test
    void createProduct_shouldReturnBadRequest_whenPriceNotPositive() throws Exception {
        String invalidJson = """
                {
                    "sku": "SKU-001",
                    "name": "Test Product",
                    "price": 0,
                    "characteristics": "{}",
                    "description": "Test"
                }
                """;

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.price").exists());
    }

    @Test
    void createProduct_shouldReturnCreated_whenValidRequest() throws Exception {
        UUID productId = UUID.randomUUID();
        CreateProductRequest request = new CreateProductRequest(
                "SKU-001", "Test Product", new BigDecimal("99.99"),
                "{\"color\":\"black\"}", "Test description"
        );

        Product savedProduct = new Product();
        savedProduct.setId(productId);
        savedProduct.setSku(request.sku());
        savedProduct.setName(request.name());
        savedProduct.setPrice(request.price());
        savedProduct.setCharacteristics(request.characteristics());
        savedProduct.setImages(new byte[0][]);
        savedProduct.setDescription(request.description());

        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(savedProduct);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Test Product"))
                .andExpect(jsonPath("$.price").value(99.99))
                .andExpect(jsonPath("$.description").value("Test description"));

        verify(productService).createProduct(any(CreateProductRequest.class));
    }
}
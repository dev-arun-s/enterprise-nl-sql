package com.nl2sql.controller;

import com.nl2sql.model.FavouriteQuery;
import com.nl2sql.service.FavouritesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/favourites")
@RequiredArgsConstructor
public class FavouritesController {

    private final FavouritesService favouritesService;

    /** GET /api/favourites */
    @GetMapping
    public ResponseEntity<List<FavouriteQuery>> getAll() {
        return ResponseEntity.ok(favouritesService.getAll());
    }

    /** POST /api/favourites */
    @PostMapping
    public ResponseEntity<FavouriteQuery> add(@Valid @RequestBody FavouriteQuery favourite) {
        return ResponseEntity.ok(favouritesService.add(favourite));
    }

    /** DELETE /api/favourites/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        boolean deleted = favouritesService.delete(id);
        if (!deleted) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}

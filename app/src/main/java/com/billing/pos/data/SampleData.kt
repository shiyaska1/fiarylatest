package com.billing.pos.data

/** A ready-to-load sample item for a business type. */
data class SampleItem(val name: String, val category: String, val price: Double, val unit: String = "PCS", val chemical: String = "")

/** Curated starter item lists per business type, loaded on demand from Settings. */
object SampleData {

    fun itemsFor(type: String): List<SampleItem> = when (type) {
        "Restaurant" -> restaurant
        "Grocery" -> grocery
        "Medical store" -> medical
        "Textiles" -> textiles
        "Mobile shop" -> mobile
        "Electrical & plumbing" -> electrical
        "Automobiles" -> automobiles
        else -> emptyList()
    }

    private val restaurant = listOf(
        SampleItem("Veg Manchurian", "Starters", 120.0),
        SampleItem("Chicken 65", "Starters", 180.0),
        SampleItem("Paneer Butter Masala", "Main Course", 220.0),
        SampleItem("Butter Chicken", "Main Course", 260.0),
        SampleItem("Butter Naan", "Breads", 40.0),
        SampleItem("Tandoori Roti", "Breads", 20.0),
        SampleItem("Veg Biryani", "Rice", 160.0),
        SampleItem("Chicken Biryani", "Rice", 200.0),
        SampleItem("Masala Dosa", "South Indian", 90.0),
        SampleItem("Cold Coffee", "Beverages", 90.0),
        SampleItem("Gulab Jamun", "Desserts", 60.0)
    )

    private val grocery = listOf(
        SampleItem("Rice 1kg", "Grains", 60.0, "KG"),
        SampleItem("Wheat Flour 1kg", "Grains", 45.0, "KG"),
        SampleItem("Sugar 1kg", "Grains", 45.0, "KG"),
        SampleItem("Toor Dal 1kg", "Grains", 120.0, "KG"),
        SampleItem("Sunflower Oil 1L", "Oils", 140.0, "LTR"),
        SampleItem("Salt 1kg", "Spices", 25.0, "KG"),
        SampleItem("Turmeric 100g", "Spices", 30.0),
        SampleItem("Tea Powder 250g", "Beverages", 130.0),
        SampleItem("Milk 500ml", "Dairy", 30.0),
        SampleItem("Biscuits", "Snacks", 20.0)
    )

    private val medical = listOf(
        SampleItem("Paracetamol 500mg", "Tablets", 25.0, "STRIP", "Paracetamol 500mg"),
        SampleItem("Amoxicillin 500mg", "Tablets", 60.0, "STRIP", "Amoxicillin 500mg"),
        SampleItem("Cetirizine 10mg", "Tablets", 20.0, "STRIP", "Cetirizine 10mg"),
        SampleItem("Azithromycin 500mg", "Tablets", 90.0, "STRIP", "Azithromycin 500mg"),
        SampleItem("Pantoprazole 40mg", "Tablets", 55.0, "STRIP", "Pantoprazole 40mg"),
        SampleItem("Metformin 500mg", "Tablets", 35.0, "STRIP", "Metformin HCl 500mg"),
        SampleItem("Amlodipine 5mg", "Tablets", 30.0, "STRIP", "Amlodipine 5mg"),
        SampleItem("Ibuprofen 400mg", "Tablets", 28.0, "STRIP", "Ibuprofen 400mg"),
        SampleItem("Cough Syrup 100ml", "Syrups", 85.0, "BOTTLE", "Dextromethorphan"),
        SampleItem("ORS Sachet", "Others", 15.0, "PCS", "Oral Rehydration Salts")
    )

    private val textiles = listOf(
        SampleItem("Cotton Shirt", "Men", 599.0),
        SampleItem("Formal Trouser", "Men", 899.0),
        SampleItem("Jeans", "Men", 1099.0),
        SampleItem("Saree", "Women", 1299.0),
        SampleItem("Kurti", "Women", 799.0),
        SampleItem("Kids T-Shirt", "Kids", 299.0),
        SampleItem("Bedsheet", "Home", 699.0),
        SampleItem("Towel", "Home", 199.0),
        SampleItem("Cotton Fabric 1m", "Fabric", 150.0, "METER"),
        SampleItem("Silk Fabric 1m", "Fabric", 450.0, "METER")
    )

    private val mobile = listOf(
        SampleItem("Phone Charger", "Accessories", 299.0),
        SampleItem("USB Cable", "Accessories", 149.0),
        SampleItem("Earphones", "Accessories", 399.0),
        SampleItem("Neckband", "Accessories", 799.0),
        SampleItem("Bluetooth Speaker", "Accessories", 999.0),
        SampleItem("Power Bank 10000mAh", "Accessories", 899.0),
        SampleItem("Tempered Glass", "Accessories", 149.0),
        SampleItem("Phone Cover", "Accessories", 199.0),
        SampleItem("Memory Card 32GB", "Accessories", 349.0),
        SampleItem("Smart Watch", "Accessories", 1499.0)
    )

    private val electrical = listOf(
        SampleItem("LED Bulb 9W", "Electrical", 90.0),
        SampleItem("Switch", "Electrical", 45.0),
        SampleItem("Wire 1m", "Electrical", 25.0, "METER"),
        SampleItem("Extension Board", "Electrical", 350.0),
        SampleItem("MCB", "Electrical", 220.0),
        SampleItem("Ceiling Fan", "Electrical", 1499.0),
        SampleItem("PVC Pipe 1m", "Plumbing", 80.0, "METER"),
        SampleItem("Tap", "Plumbing", 250.0),
        SampleItem("Elbow Joint", "Plumbing", 30.0),
        SampleItem("Teflon Tape", "Plumbing", 20.0)
    )

    private val automobiles = listOf(
        SampleItem("Engine Oil 1L", "Oils", 450.0, "LTR"),
        SampleItem("Air Filter", "Spares", 350.0),
        SampleItem("Brake Pad Set", "Spares", 800.0),
        SampleItem("Spark Plug", "Spares", 120.0),
        SampleItem("Clutch Plate", "Spares", 1200.0),
        SampleItem("Headlight Bulb", "Spares", 180.0),
        SampleItem("Battery", "Spares", 3500.0),
        SampleItem("Wiper Blade", "Accessories", 250.0),
        SampleItem("Car Perfume", "Accessories", 150.0),
        SampleItem("Seat Cover", "Accessories", 1200.0)
    )
}

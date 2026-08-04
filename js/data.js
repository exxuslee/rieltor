/*
 * Локальна добірка для макета. Назви ЖК і базові стартові параметри звірені
 * з публічним каталогом станом на 02.08.2026; наявність і вартість завжди
 * потребують підтвердження перед публікацією як реальних пропозицій.
 * Фото — власні локальні ілюстрації, не з каталогів забудовників чи агентств.
 */
window.PROPERTIES = [
    {
        id: 'apartment-central-park', category: 'apartments', title: 'Світла квартира біля Центрального парку',
        location: 'Ірпінь, вул. Чехова', price: 82000, rooms: 2, area: 64, floor: '7 із 10', year: 2022,
        image: 'images/properties/apartment-park.png',
        description: 'Простора квартира з панорамними вікнами, кухнею-вітальнею та сучасним ремонтом. Поруч парк, магазини й зручний виїзд до Києва.'
    },
    {
        id: 'apartment-new-turnkey', category: 'apartments', title: 'Однокімнатна квартира з ремонтом',
        location: 'Ірпінь, Новооскольська', price: 57000, rooms: 1, area: 43, floor: '6 із 10', year: 2023,
        image: 'images/properties/apartment-new.png',
        description: 'Затишна квартира у новому будинку з меблями й технікою. Практичний варіант для першого житла або інвестиції.'
    },
    {
        id: 'apartment-two-room-irpin', category: 'apartments', title: 'Двокімнатна квартира для сім’ї',
        location: 'Ірпінь, район набережної', price: 69500, rooms: 2, area: 61, floor: '4 із 9', year: 2021,
        image: 'images/properties/apartment-park.png',
        description: 'Функціональне планування з двома окремими кімнатами, світлою кухнею та місцем для зберігання.'
    },
    {
        id: 'apartment-three-room-bucha', category: 'apartments', title: 'Трикімнатна квартира у Бучі',
        location: 'Буча, центральна частина', price: 98500, rooms: 3, area: 82, floor: '3 із 10', year: 2020,
        image: 'images/properties/apartment-new.png',
        description: 'Простора оселя для родини: три кімнати, два санвузли та балкон. Повсякденна інфраструктура — поруч.'
    },
    {
        id: 'apartment-studio-irpin', category: 'apartments', title: 'Компактна студія з балконом',
        location: 'Ірпінь, район вокзалу', price: 39500, rooms: 1, area: 32, floor: '8 із 10', year: 2024,
        image: 'images/properties/apartment-new.png',
        description: 'Світла студія з продуманою зоною кухні та власним балконом. Зручний стартовий формат для життя чи оренди.'
    },

    {
        id: 'rc-olymp',
        category: 'new-buildings',
        title: 'ЖК «Олімп»',
        location: 'Ірпінь',
        price: 850,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 1,
        area: 38,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Добірка планувань у житловому комплексі. Фінальну площу, секцію та умови придбання уточнюйте перед переглядом.'
    },
    {
        id: 'rc-city-park-2',
        category: 'new-buildings',
        title: 'ЖК «City Park 2»',
        location: 'Ірпінь',
        price: 600,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 1,
        area: 35,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Новобудова для тих, хто шукає квартиру поруч із міською інфраструктурою. Підберемо актуальне планування.'
    },
    {
        id: 'rc-central-2',
        category: 'new-buildings',
        title: 'ЖК «Центральний-2»',
        location: 'Ірпінь, центр',
        price: 800,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 2,
        area: 55,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Варіанти квартир у центральній частині міста. Доступність конкретних поверхів і ціну підтвердимо за запитом.'
    },
    {
        id: 'rc-chehov-park',
        category: 'new-buildings',
        title: 'ЖК «Chehov Парк Квартал»',
        location: 'Ірпінь, район Центрального парку',
        price: 750,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 2,
        area: 56,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Житловий комплекс у парковому районі. Допоможемо порівняти формати квартир та підібрати зручний поверх.'
    },
    {
        id: 'rc-molodist',
        category: 'new-buildings',
        title: 'ЖК «Молодість»',
        location: 'Ірпінь',
        price: 750,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 1,
        area: 40,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Сучасний квартал із квартирами різної площі. Зверніться, щоб отримати актуальні варіанти та умови.'
    },
    {
        id: 'rc-synergia-city',
        category: 'new-buildings',
        title: 'ЖК «Синергія Сіті»',
        location: 'Ірпінь',
        price: 750,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 2,
        area: 60,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Новобудова з вибором сімейних і компактних планувань. Актуальний список пропозицій формується індивідуально.'
    },
    {
        id: 'rc-syayvo-2',
        category: 'new-buildings',
        title: 'ЖК «Сяйво-2»',
        location: 'Ірпінь',
        price: 550,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 1,
        area: 34,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Добірка доступних за стартовою ціною квартир. Перевіримо наявність і відповідність вашому бюджету.'
    },
    {
        id: 'rc-sky-2',
        category: 'new-buildings',
        title: 'ЖК «Sky-2»',
        location: 'Ірпінь',
        price: 555,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 1,
        area: 36,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Варіанти в новому комплексі для проживання або інвестиції. Уточніть планування, яке підходить саме вам.'
    },
    {
        id: 'rc-irpin-city',
        category: 'new-buildings',
        title: 'ЖК «Irpin City»',
        location: 'Ірпінь',
        price: 566,
        pricePrefix: 'від ',
        priceSuffix: ' $/м²',
        rooms: 2,
        area: 52,
        floor: 'варіанти планувань',
        year: 0,
        image: 'images/properties/new-building.png',
        description: 'Квартири з різними площами у межах міста. Проведемо консультацію щодо актуальних умов купівлі.'
    },
    {
        id: 'new-building-family', category: 'new-buildings', title: 'Квартира у новому житловому кварталі',
        location: 'Ірпінь, район Центрального парку', price: 64500, rooms: 2, area: 58, floor: '5 із 9', year: 2024,
        image: 'images/properties/new-building.png',
        description: 'Нова квартира у сучасному комплексі із закритим двором, дитячими зонами та власною інфраструктурою.'
    },

    {
        id: 'house-terrace-bucha', category: 'houses', title: 'Будинок із терасою в тихому районі',
        location: 'Буча, Лісова частина', price: 168000, rooms: 4, area: 146, floor: '2 поверхи', year: 2023,
        image: 'images/properties/house-bucha.png',
        description: 'Сучасний сімейний будинок із продуманим плануванням, відкритою терасою та озелененою ділянкою.'
    },
    {
        id: 'duplex-ready-renovation', category: 'houses', title: 'Дуплекс для старту ремонту',
        location: 'Ірпінь', price: 107000, rooms: 4, area: 150, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Просторий дуплекс із сучасним форматом планування. Підійде тим, хто хоче реалізувати власний інтер’єр.'
    },
    {
        id: 'house-renovated-irpin', category: 'houses', title: 'Будинок із готовим ремонтом',
        location: 'Ірпінь', price: 225000, rooms: 5, area: 330, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Великий будинок для родини з гаражем на два авто та завершеним ремонтом. Деталі комплектації — за запитом.'
    },
    {
        id: 'cottage-horenychi', category: 'houses', title: 'Котедж під індивідуальний ремонт',
        location: 'с. Гореничі', price: 130000, rooms: 4, area: 150, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Котедж із власним паркомісцем у передмісті Києва. Можна облаштувати простір відповідно до свого стилю.'
    },
    {
        id: 'duplex-central-bucha', category: 'houses', title: 'Дуплекс у центрі Бучі',
        location: 'Буча, центр', price: 147000, rooms: 3, area: 97.3, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Компактний дуплекс із ремонтом у зручній міській локації. Вдалий формат для невеликої родини.'
    },
    {
        id: 'house-partial-renovation', category: 'houses', title: 'Сучасний будинок із готовим базовим ремонтом',
        location: 'Бучанський район', price: 187000, rooms: 4, area: 180, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Будинок із виконаною основною частиною ремонту та паркуванням для кількох автомобілів.'
    },
    {
        id: 'house-terrace-bucha-135', category: 'houses', title: 'Будинок із терасою для комфортного життя',
        location: 'Буча', price: 265000, rooms: 4, area: 135, floor: '1 поверх', year: 2022,
        image: 'images/properties/house-bucha.png',
        description: 'Одноповерховий формат із терасою та власним паркомісцем. Уточнюйте стан об’єкта перед переглядом.'
    },
    {
        id: 'house-mezhyrichchia',
        category: 'houses',
        title: 'Будинок у котеджному містечку',
        location: 'КМ «Межиріччя»',
        price: 109000,
        pricePrefix: 'від ',
        rooms: 4,
        area: 152,
        floor: '2 поверхи',
        year: 2022,
        image: 'images/properties/house-bucha.png',
        description: 'Будинок у котеджному містечку з власним паркомісцем. Доступність конкретної секції необхідно підтвердити.'
    },
    {
        id: 'duplex-stoyanka', category: 'houses', title: 'Дуплекс у Стоянці',
        location: 'Стоянка', price: 55000, pricePrefix: 'від ', rooms: 3, area: 105, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Формат заміського житла з власним паркомісцем за стартовою ціною квартири.'
    },
    {
        id: 'townhouse-lisne',
        category: 'houses',
        title: 'Таунхаус у котеджному містечку',
        location: 'Бучанський район',
        price: 70000,
        pricePrefix: 'від ',
        rooms: 3,
        area: 100,
        floor: '2 поверхи',
        year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Дворівневий таунхаус для тих, хто цінує приватний формат і близькість до міської інфраструктури.'
    },
    {
        id: 'townhouse-bucha-center', category: 'houses', title: 'Таунхаус у центрі Бучі',
        location: 'Буча, центр', price: 59900, rooms: 3, area: 100, floor: '2 поверхи', year: 2016,
        image: 'images/properties/house-bucha.png',
        description: 'Варіант під ремонт у центральній частині Бучі. Зручна основа, щоб створити житло під свій сценарій.'
    },

    {
        id: 'commercial-center', category: 'commercial', title: 'Комерційне приміщення у центрі',
        location: 'Ірпінь, центральна частина', price: 94000, rooms: 0, area: 92, floor: '1 поверх', year: 2021,
        image: 'images/properties/commercial.png',
        description: 'Фасадне приміщення з великими вітринами та вільним плануванням. Підійде для шоуруму, офісу або сервісного бізнесу.'
    },
    {
        id: 'land-pines-bucha', category: 'land', title: 'Ділянка поруч із сосновим лісом',
        location: 'Бучанський район', price: 39000, rooms: 0, area: 1000, floor: '10 соток', year: 0,
        image: 'images/properties/land-bucha.png',
        description: 'Рівна ділянка під житлове будівництво. Поруч електрика, зручний під’їзд і тиха природна локація.'
    }
];

window.CATEGORY_LABELS = {
    apartments: 'Квартири',
    'new-buildings': 'Новобудови',
    houses: 'Будинки',
    land: 'Земля',
    commercial: 'Комерція'
};

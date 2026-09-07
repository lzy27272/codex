export const dashboardApiPaths = Object.freeze({
  accessibleHotels: '/dashboards/hotels',
  hotel: (hotelId: string) => `/dashboards/hotels/${hotelId}`,
  operations: '/dashboards/operations',
})
